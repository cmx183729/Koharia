package koharia.scan

import koharia.storage.LibraryStorageBackend
import koharia.storage.StorageCapabilities
import koharia.storage.StorageEntry
import koharia.storage.StorageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorageScanEngineTest {
    @Test
    fun `scan visits nested directories and returns supported files in path order`() = runBlocking {
        val backend = FixtureBackend(
            directories = setOf("", "library", "library/nested"),
            files = mapOf(
                "library/nested/page-2.jpg" to "b".encodeToByteArray(),
                "library/page-1.png" to "a".encodeToByteArray(),
                "library/notes.txt" to "ignored".encodeToByteArray(),
            ),
        )

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("library")

        assertEquals(listOf("library/nested/page-2.jpg", "library/page-1.png"), result.map { it.path })
        assertTrue(result.all { !it.archive })
    }

    @Test
    fun `request interval is not applied to listing-only image entries`() = runBlocking {
        val backend = FixtureBackend(setOf(""), (1..20).associate { "$it.jpg" to byteArrayOf(1) })

        val result = withTimeout(5_000) {
            StorageScanEngine(backend, concurrency = 1, requestIntervalMs = 60_000).scan("")
        }

        assertEquals(20, result.size)
    }

    @Test
    fun `request interval still throttles archive content reads`() = runBlocking {
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to zipOf("ametadata" to "{\"title\":\"T\"}")))
        val started = System.nanoTime()

        StorageScanEngine(backend, requestIntervalMs = 200).scan("")

        assertTrue((System.nanoTime() - started) / 1_000_000 >= 200)
    }

    @Test
    fun `image detection follows the shared local media formats`() = runBlocking {
        val backend = FixtureBackend(
            setOf(""),
            listOf("a.AVIF", "b.heic", "c.jxl", "d.bmp", "e.txt").associateWith { byteArrayOf(1) },
        )

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("")

        assertEquals(listOf("a.AVIF", "b.heic", "c.jxl"), result.map { it.path })
        assertTrue(backend.readPaths.isEmpty())
    }

    @Test
    fun `image scan does not read file content`() = runBlocking {
        val backend = FixtureBackend(setOf(""), mapOf("page.jpg" to byteArrayOf(1, 2, 3)))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("")

        assertEquals(listOf("page.jpg"), result.map { it.path })
        assertTrue(backend.readPaths.isEmpty())
    }

    @Test
    fun `incremental scan skips unchanged entries unless forced`() = runBlocking {
        val backend = FixtureBackend(
            directories = setOf(""),
            files = mapOf("book.jpg" to byteArrayOf(1, 2, 3)),
        )
        val first = StorageScanEngine(backend, requestIntervalMs = 0).scan("")
        val previous = first.associate { it.path to it.fingerprint }

        assertTrue(StorageScanEngine(backend, requestIntervalMs = 0, previous = previous).scan("").isEmpty())
        assertEquals(
            1,
            StorageScanEngine(backend, requestIntervalMs = 0, previous = previous).scan("", forceFull = true).size,
        )
    }

    @Test
    fun `scan cancellation propagates to an in-flight directory listing`() = runBlocking {
        val listingStarted = CompletableDeferred<Unit>()
        val backend = FixtureBackend(setOf(""), emptyMap(), listingStarted)
        val scan = launch { StorageScanEngine(backend, requestIntervalMs = 0).scan("") }

        listingStarted.await()
        scan.cancelAndJoin()

        assertTrue(backend.listCancelled)
    }

    @Test
    fun `archive metadata exceeding the size limit is rejected`() {
        val archive = zipOf("ametadata" to "a".repeat(2 * 1024 * 1024 + 1))
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { StorageScanEngine(backend, requestIntervalMs = 0).scan("") }
        }

        assertEquals("Archive entry exceeds size limit", exception.message)
    }

    @Test
    fun `oversized stored metadata is rejected before its payload is transferred`() {
        val payload = ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }
        val archive = java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry("ComicInfo.xml").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = payload.size.toLong()
                    crc = java.util.zip.CRC32().also { it.update(payload) }.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
        }.toByteArray()
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { StorageScanEngine(backend, requestIntervalMs = 0).scan("") }
        }

        assertEquals("Archive metadata exceeds the supported size", exception.message)
        assertTrue(backend.readLengths.none { it >= payload.size })
    }

    @Test
    fun `archive without an end directory record fails scanning`() {
        val backend = FixtureBackend(setOf(""), mapOf("broken.cbz" to "not a zip".encodeToByteArray()))

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { StorageScanEngine(backend, requestIntervalMs = 0).scan("") }
        }

        assertEquals("ZIP end of central directory not found", exception.message)
    }

    @Test
    fun `archive metadata accepts ametadata json extension`() = runBlocking {
        val archive = zipOf("ametadata.json" to "{\"title\":\"JSON export\"}")
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("").single()

        assertEquals("JSON export", result.metadata?.title)
    }

    @Test
    fun `comic info takes precedence over both json metadata names`() = runBlocking {
        val archive = zipOf(
            "ametadata.json" to "{\"title\":\"JSON extension\"}",
            "ametadata" to "{\"title\":\"Extensionless JSON\"}",
            "ComicInfo.xml" to "<ComicInfo><Title>Comic info</Title></ComicInfo>",
        )
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("").single()

        assertEquals("Comic info", result.metadata?.title)
    }

    @Test
    fun `root metadata takes precedence over nested metadata listed first`() = runBlocking {
        val archive = zipOf(
            "chapter/ComicInfo.xml" to "<ComicInfo><Title>Nested</Title></ComicInfo>",
            "ComicInfo.xml" to "<ComicInfo><Title>Root</Title></ComicInfo>",
        )
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("").single()

        assertEquals("Root", result.metadata?.title)
    }

    @Test
    fun `nested metadata is used when the archive root has none`() = runBlocking {
        val archive = zipOf("folder/ComicInfo.xml" to "<ComicInfo><Title>Nested</Title></ComicInfo>")
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("").single()

        assertEquals("Nested", result.metadata?.title)
    }

    @Test
    fun `extensionless metadata takes precedence over json extension`() = runBlocking {
        val archive = zipOf(
            "ametadata.json" to "{\"title\":\"JSON extension\"}",
            "ametadata" to "{\"title\":\"Extensionless JSON\"}",
        )
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("").single()

        assertEquals("Extensionless JSON", result.metadata?.title)
    }

    @Test
    fun `archive reads respect configured concurrency`() = runBlocking {
        val archive = zipOf("ComicInfo.xml" to "<ComicInfo><Title>Test</Title></ComicInfo>")
        val backend = FixtureBackend(setOf(""), mapOf("a.cbz" to archive, "b.cbz" to archive))

        StorageScanEngine(backend, concurrency = 1, requestIntervalMs = 0).scan("")

        assertEquals(1, backend.maxConcurrentReads)
    }

    @Test
    fun `archive scan reads bounded metadata without downloading image entries`() = runBlocking {
        val archive = zipOf("ametadata" to "{\"title\":\"Test\",\"tags\":\"artist:a\"}")
        val backend = FixtureBackend(setOf(""), mapOf("book.cbz" to archive))

        val result = StorageScanEngine(backend, requestIntervalMs = 0).scan("").single()

        assertTrue(result.archive)
        assertEquals("Test", result.metadata?.title)
        assertTrue(backend.readPaths.isNotEmpty())
        assertTrue(backend.readPaths.all { it == "book.cbz" })
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = buildList {
        entries.forEach { (name, value) -> add(name to value.encodeToByteArray()) }
    }.let { files ->
        java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private class FixtureBackend(
        directories: Set<String>,
        files: Map<String, ByteArray>,
        private val listingStarted: CompletableDeferred<Unit>? = null,
    ) : LibraryStorageBackend {
        private val directories = directories.toSet()
        private val files = files.mapValues { it.value.copyOf() }
        val readPaths = mutableListOf<String>()
        val readLengths = mutableListOf<Int>()
        var listCancelled = false
        var maxConcurrentReads = 0
            private set
        private var concurrentReads = 0
        override val capabilities = StorageCapabilities(false, true, false, false)

        override suspend fun stat(path: String): StorageEntry = entry(path)

        override suspend fun list(path: String): List<StorageEntry> {
            listingStarted?.complete(Unit)
            if (listingStarted != null) {
                try {
                    awaitCancellation()
                } catch (exception: CancellationException) {
                    listCancelled = true
                    throw exception
                }
            }
            return (directories + files.keys)
                .filter { it != path && it.substringBeforeLast('/', "") == path }
                .map(::entry)
        }

        override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
            readPaths += entry.path
            readLengths += length
            concurrentReads++
            maxConcurrentReads = maxOf(maxConcurrentReads, concurrentReads)
            return try {
                delay(1)
                files.getValue(entry.path).copyOfRange(offset.toInt(), offset.toInt() + length)
            } finally {
                concurrentReads--
            }
        }

        override suspend fun createDirectory(
            path: String,
        ) = unsupported()

        override suspend fun write(
            path: String,
            data: InputStream,
            length: Long,
            expectedVersion: String?,
        ) = unsupported()

        override suspend fun move(
            entry: StorageEntry,
            destination: String,
        ) {
            unsupported()
        }

        override suspend fun delete(entry: StorageEntry) = unsupported()

        override fun close() = Unit

        private fun entry(path: String): StorageEntry {
            if (path in directories) return StorageEntry(path, true)
            val bytes = files[path] ?: error("Missing fixture entry $path")
            return StorageEntry(path, false, bytes.size.toLong(), version = "v1")
        }

        private fun unsupported(): Nothing = throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
    }
}
