package koharia.scan

import koharia.storage.LibraryStorageBackend
import koharia.storage.StorageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/** Incremental, bounded scanner for local and WebDAV storage backends. */
class StorageScanEngine(
    private val backend: LibraryStorageBackend,
    private val concurrency: Int = 3,
    private val requestIntervalMs: Long = 200,
    private val incremental: Boolean = true,
    private val previous: Map<String, ScanFingerprint> = emptyMap(),
) {
    suspend fun scan(root: String, forceFull: Boolean = false): List<ScannedStorageItem> = withContext(Dispatchers.IO) {
        val gate = Semaphore(concurrency.coerceIn(1, 16))
        val result = mutableListOf<ScannedStorageItem>()
        suspend fun visit(path: String) = coroutineScope {
            val entries = backend.list(path)
            entries.filter { it.directory }.forEach { visit(it.path) }
            entries.filterNot { it.directory }.map { entry ->
                async {
                    gate.withPermit {
                        val fingerprint = ScanFingerprint(entry.size, entry.modifiedAt, entry.version)
                        if (incremental && !forceFull && previous[entry.path] == fingerprint) return@withPermit null
                        if (requestIntervalMs > 0) delay(requestIntervalMs)
                        scanFile(entry, fingerprint)
                    }
                }
            }.forEach { it.await()?.let(result::add) }
        }
        visit(root)
        result.sortedBy { it.path }
    }

    private suspend fun scanFile(entry: StorageEntry, fingerprint: ScanFingerprint): ScannedStorageItem? {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        if (extension in IMAGE_EXTENSIONS) return ScannedStorageItem(entry.path, fingerprint, null, false)
        if (extension !in ARCHIVE_EXTENSIONS) return null
        val directory = ZipRangeDirectory.read(backend, entry)
        val metadata = directory.readFirst("ComicInfo.xml")?.let(ArchiveMetadataParser::comicInfo)
            ?: directory.readFirst("ametadata")?.let(ArchiveMetadataParser::ametadata)
        return ScannedStorageItem(entry.path, fingerprint, metadata, true)
    }
}

data class ScanFingerprint(val size: Long, val modifiedAt: Long, val version: String)
data class ScannedStorageItem(val path: String, val fingerprint: ScanFingerprint, val metadata: ArchiveMetadata?, val archive: Boolean)

private class ZipRangeDirectory private constructor(
    private val backend: LibraryStorageBackend,
    private val entry: StorageEntry,
    private val records: Map<String, Record>,
) {
    suspend fun readFirst(name: String): ByteArray? = records.entries.firstOrNull { it.key.substringAfterLast('/').equals(name, true) }
        ?.value?.let { read(it) }

    private suspend fun read(record: Record): ByteArray {
        val local = backend.read(entry, record.offset, 30)
        check(le(local, 0) == 0x04034b50) { "Invalid ZIP local header" }
        val nameLength = us(local, 26)
        val extraLength = us(local, 28)
        val compressed = backend.read(entry, record.offset + 30 + nameLength + extraLength, record.compressedSize)
        val output = when (record.method) {
            0 -> compressed
            8 -> InflaterInputStream(ByteArrayInputStream(compressed), java.util.zip.Inflater(true)).use { it.readBounded(MAX_ENTRY_BYTES) }
            else -> error("Unsupported ZIP compression method")
        }
        check(output.size <= MAX_ENTRY_BYTES) { "Archive metadata exceeds the supported size" }
        return output
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        suspend fun read(backend: LibraryStorageBackend, entry: StorageEntry): ZipRangeDirectory {
            val tailLength = minOf(entry.size, 65_557L).toInt()
            val tailOffset = entry.size - tailLength
            val tail = backend.read(entry, tailOffset, tailLength)
            val eocd = (tail.size - 22 downTo 0).firstOrNull { le(tail, it) == 0x06054b50 }
                ?: error("ZIP end of central directory not found")
            check(us(tail, eocd + 4) == 0 && us(tail, eocd + 6) == 0) { "Multi-disk ZIP is unsupported" }
            val count = us(tail, eocd + 10)
            val centralSize = uint(tail, eocd + 12)
            val centralOffset = uint(tail, eocd + 16)
            check(centralSize <= 16L * 1024 * 1024 && centralOffset + centralSize <= entry.size) { "Invalid ZIP directory bounds" }
            val central = backend.read(entry, centralOffset, centralSize.toInt())
            check(central.size.toLong() == centralSize) { "Truncated ZIP directory" }
            var cursor = 0
            val records = buildMap {
                repeat(count) {
                    check(cursor >= 0 && cursor + 46 <= central.size && le(central, cursor) == 0x02014b50) { "Invalid ZIP central directory" }
                    val method = us(central, cursor + 10)
                    val compressed = uint(central, cursor + 20).toInt()
                    val nameLength = us(central, cursor + 28)
                    val extraLength = us(central, cursor + 30)
                    val commentLength = us(central, cursor + 32)
                    val localOffset = uint(central, cursor + 42)
                    val recordEnd = cursor + 46 + nameLength + extraLength + commentLength
                    check(recordEnd <= central.size && localOffset < entry.size && compressed >= 0) { "Invalid ZIP entry bounds" }
                    val name = central.copyOfRange(cursor + 46, cursor + 46 + nameLength).toString(Charsets.UTF_8)
                    put(name, Record(localOffset, compressed, method))
                    cursor = recordEnd
                }
            }
            return ZipRangeDirectory(backend, entry, records)
        }
        private fun le(b: ByteArray, o: Int) = (us(b, o) or (us(b, o + 2) shl 16))
        private fun us(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        private fun uint(b: ByteArray, o: Int) = (us(b, o).toLong() or (us(b, o + 2).toLong() shl 16)).coerceAtMost(Int.MAX_VALUE.toLong())
        private data class Record(val offset: Long, val compressedSize: Int, val method: Int)
    }
}

private fun java.io.InputStream.readBounded(max: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        check(output.size() <= max) { "Archive entry exceeds size limit" }
    }
    return output.toByteArray()
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
