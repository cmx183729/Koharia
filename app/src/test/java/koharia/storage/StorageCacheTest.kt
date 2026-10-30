package koharia.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StorageCacheTest {
    @TempDir lateinit var temporary: Path

    @Test fun `open ranged file can exceed cache budget and reread evicted blocks`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("book", "x".repeat(StorageBlockCache.BLOCK_SIZE * 3)) }
        val entry = backend.stat("book")
        val budget = StorageBlockCache.BLOCK_SIZE.toLong() * 2
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { budget }
        cache.acquire(entry).use {
            coroutineScope {
                (0..20).map { index ->
                    async {
                        assertArrayEquals(
                            byteArrayOf('x'.code.toByte()),
                            cache.read(entry, (index % 3).toLong() * StorageBlockCache.BLOCK_SIZE, 1),
                        )
                    }
                }.awaitAll()
            }
            assertArrayEquals(byteArrayOf('x'.code.toByte()), cache.read(entry, 0, 1))
            org.junit.jupiter.api.Assertions.assertTrue(
                temporary.toFile().walkTopDown().filter { it.extension == "block" }.sumOf(File::length) <= budget,
            )
        }
    }

    @Test fun `short range response for final partial block is rejected and not cached`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("page.jpg", "final-page") }
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray =
                memory.read(entry, offset, length - 1)
        }
        val cache = StorageBlockCache(
            temporary.toFile(),
            StorageSession(1, "account", "root") { true },
            backend,
        ) { 1024 }
        val entry = backend.stat("page.jpg")

        val error = assertThrows(StorageFailure::class.java) {
            runBlocking { cache.read(entry, 0, entry.size.toInt()) }
        }

        assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        assertEquals(0, temporary.toFile().walkTopDown().count { it.extension == "block" })
    }

    @Test fun `complete file remains protected while its reader holds a lease`() = runBlocking {
        val backend = MemoryStorageBackend().apply {
            seed("book", "x".repeat(StorageBlockCache.BLOCK_SIZE * 2))
            seed("other", "y".repeat(StorageBlockCache.BLOCK_SIZE))
        }
        val entry = backend.stat("book")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        val complete = cache.prepareComplete(entry)
        cache.acquire(entry).use {
            val error =
                assertThrows(StorageFailure::class.java) { runBlocking { cache.read(backend.stat("other"), 0, 1) } }
            assertEquals(StorageFailure.Reason.SPACE, error.reason)
            org.junit.jupiter.api.Assertions.assertTrue(complete.isFile)
            assertArrayEquals(byteArrayOf('x'.code.toByte()), cache.read(entry, 0, 1))
        }
        assertArrayEquals(byteArrayOf('y'.code.toByte()), cache.read(backend.stat("other"), 0, 1))
    }

    @Test fun `concurrent ranges share a block and corrupt blocks are refetched`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "x".repeat(400000)) }
        val reads = AtomicInteger()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
                reads.incrementAndGet()
                return memory.read(entry, offset, length)
            }
        }
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { 1024 * 1024 }
        val entry = backend.stat("book")
        coroutineScope { (1..12).map { async { cache.read(entry, 100, 32) } }.awaitAll() }
        assertEquals(1, reads.get())
        val block = temporary.toFile().walkTopDown().single { it.extension == "block" }
        block.writeBytes(ByteArray(block.length().toInt()))
        assertArrayEquals(ByteArray(32) { 'x'.code.toByte() }, cache.read(entry, 100, 32))
        assertEquals(2, reads.get())
    }

    @Test fun `full fallback validates content after corruption and respects budget`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "z".repeat(400000)) }
        val copies = AtomicInteger()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray =
                throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                copies.incrementAndGet()
                memory.copyTo(entry, output)
            }
        }
        val session = StorageSession(1, "a", "r") { true }
        val cache = StorageBlockCache(temporary.toFile(), session, backend) { 1024 * 1024 }
        val entry = backend.stat("book")
        assertArrayEquals(ByteArray(32) { 'z'.code.toByte() }, cache.read(entry, 100, 32))
        val complete = temporary.toFile().walkTopDown().single { it.name == "complete.bin" }
        complete.writeBytes(ByteArray(400000))
        complete.setLastModified(1)
        assertArrayEquals(ByteArray(32) { 'z'.code.toByte() }, cache.read(entry, 100, 32))
        assertEquals(2, copies.get())
        val limited = StorageBlockCache(File(temporary.toFile(), "small"), session, backend) { 100 }
        val error = assertThrows(StorageFailure::class.java) { runBlocking { limited.prepareComplete(entry) } }
        assertEquals(StorageFailure.Reason.SPACE, error.reason)
        assertEquals(2, copies.get())
    }

    @Test fun `cold known image stream copies once and reopening needs no backend reads`() = runBlocking {
        val content = "0123456789abcdef".repeat(2 * 1024 * 1024 / 16)
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", content) })
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }

        cache.openInputStream(entry, preferComplete = true).use {
            assertArrayEquals(content.encodeToByteArray(), it.readBytes())
        }
        assertEquals(1, backend.copies.get())
        assertEquals(0, backend.reads.get())
        cache.openCompleteInputStream(entry).use {
            assertArrayEquals(content.encodeToByteArray(), it.readBytes())
        }
        cache.openInputStream(entry, preferComplete = true).use {
            assertArrayEquals(content.encodeToByteArray(), it.readBytes())
        }
        assertEquals(1, backend.copies.get())
        assertEquals(0, backend.reads.get())
    }

    @Test fun `reopening a complete image protects it from eviction ahead of an older unread image`() = runBlocking {
        val backend = CountingStorageBackend(
            MemoryStorageBackend().apply {
                seed("hot.jpg", "hhhh")
                seed("cold.jpg", "cccc")
                seed("new.jpg", "nnnn")
            },
        )
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { 8L }
        val hot = backend.stat("hot.jpg")
        val cold = backend.stat("cold.jpg")
        val hotFile = cache.prepareComplete(hot)
        val coldFile = cache.prepareComplete(cold)
        assertTrue(hotFile.setLastModified(1_000))
        assertTrue(coldFile.setLastModified(2_000))
        StorageCacheIndex.get(temporary.toFile()).invalidate()

        repeat(2) {
            cache.openCompleteInputStream(hot).use { assertArrayEquals("hhhh".encodeToByteArray(), it.readBytes()) }
        }
        assertEquals(2, backend.copies.get())
        cache.openCompleteInputStream(backend.stat("new.jpg")).use {
            assertArrayEquals("nnnn".encodeToByteArray(), it.readBytes())
        }
        assertTrue(hotFile.isFile)
        assertTrue(!coldFile.exists())
        cache.openCompleteInputStream(hot).use { assertArrayEquals("hhhh".encodeToByteArray(), it.readBytes()) }
        assertEquals(3, backend.copies.get())
        assertEquals(12L, backend.transferredBytes.get())
    }

    @Test fun `complete transfer counts mixed single and buffered writes and warm opens stay local`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("page.jpg", "abcde") }
        val copies = AtomicInteger()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                copies.incrementAndGet()
                output.write('a'.code)
                output.write("_bcd_".encodeToByteArray(), 1, 3)
                output.write(byteArrayOf(), 0, 0)
                output.write('e'.code)
            }
        }
        val entry = memory.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        repeat(3) {
            cache.openCompleteInputStream(entry).use { assertArrayEquals("abcde".encodeToByteArray(), it.readBytes()) }
        }
        assertEquals(1, copies.get())
    }

    @Test fun `oversized mixed transfer is rejected and can retry`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("page.jpg", "abc") }
        var oversized = true
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                output.write('a'.code)
                output.write("bc".encodeToByteArray())
                if (oversized) output.write('d'.code)
            }
        }
        val entry = memory.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        val error = assertThrows(StorageFailure::class.java) { runBlocking { cache.openCompleteInputStream(entry) } }
        assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        assertTrue(
            temporary.toFile().walkTopDown().none {
                it.name in
                    setOf("complete.bin", "complete.part", "complete.ok")
            },
        )
        oversized = false
        cache.openCompleteInputStream(entry).use { assertArrayEquals("abc".encodeToByteArray(), it.readBytes()) }
    }

    @Test fun `truncated complete transfer is never published and can retry`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("page.jpg", "abc") }
        var truncated = true
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                if (truncated) output.write('a'.code) else memory.copyTo(entry, output)
            }
        }
        val entry = memory.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        val error = assertThrows(StorageFailure::class.java) { runBlocking { cache.openCompleteInputStream(entry) } }
        assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        assertTrue(
            temporary.toFile().walkTopDown().none {
                it.name in
                    setOf("complete.bin", "complete.part", "complete.ok")
            },
        )
        truncated = false
        cache.openCompleteInputStream(entry).use { assertArrayEquals("abc".encodeToByteArray(), it.readBytes()) }
    }

    @Test fun `concurrent image opens share one transfer including a partial final block`() = runBlocking {
        val content = "x".repeat(StorageBlockCache.BLOCK_SIZE * 2 + 137)
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", content) })
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        coroutineScope {
            (1..8).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    cache.openInputStream(entry, preferComplete = true).use { input ->
                        assertArrayEquals(content.encodeToByteArray(), input.readBytes())
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, backend.copies.get())
        assertEquals(0, backend.reads.get())
        assertEquals(entry.size, backend.transferredBytes.get())
    }

    @Test fun `space fallback transfers only remaining bytes in its final block`() = runBlocking {
        val content = "x".repeat(StorageBlockCache.BLOCK_SIZE * 2 + 137)
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", content) })
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) {
            StorageBlockCache.BLOCK_SIZE.toLong()
        }
        cache.openInputStream(entry, preferComplete = true).use { input ->
            assertArrayEquals(content.encodeToByteArray(), input.readBytes())
        }
        assertEquals(0, backend.copies.get())
        assertEquals(3, backend.reads.get())
        assertEquals(entry.size, backend.transferredBytes.get())
    }

    @Test fun `complete stream refetches a corrupted complete file`() = runBlocking {
        val content = "image".repeat(80000)
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", content) })
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        cache.openCompleteInputStream(entry).use { assertArrayEquals(content.encodeToByteArray(), it.readBytes()) }
        val complete = temporary.toFile().walkTopDown().single { it.name == "complete.bin" }
        complete.writeBytes(ByteArray(content.length))
        assertTrue(complete.setLastModified(1))

        cache.openCompleteInputStream(entry).use { assertArrayEquals(content.encodeToByteArray(), it.readBytes()) }
        assertEquals(2, backend.copies.get())
        assertEquals(0, backend.reads.get())
    }

    @Test fun `complete stream space fallback reads each block once with eight kibibyte buffers`() = runBlocking {
        val content = "0123456789abcdef".repeat(2 * 1024 * 1024 / 16)
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", content) })
        val entry = backend.stat("page.jpg")
        val budget = StorageBlockCache.BLOCK_SIZE.toLong()
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { budget }
        val output = java.io.ByteArrayOutputStream()
        cache.openInputStream(entry, preferComplete = true).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }

        assertArrayEquals(content.encodeToByteArray(), output.toByteArray())
        assertEquals(0, backend.copies.get())
        assertEquals(content.length / StorageBlockCache.BLOCK_SIZE, backend.reads.get())
        assertEquals(
            (0 until content.length / StorageBlockCache.BLOCK_SIZE).map { it.toLong() * StorageBlockCache.BLOCK_SIZE },
            backend.rangeOffsets,
        )
        assertTrue(temporary.toFile().walkTopDown().filter { it.extension == "block" }.sumOf(File::length) <= budget)
        assertEquals(1, StorageCacheIndex.get(temporary.toFile()).access { clearUnused() })
    }

    @Test fun `small reads reuse the verified open block and reopen revalidates disk`() = runBlocking {
        val backend = CountingStorageBackend(
            MemoryStorageBackend().apply {
                seed("page.jpg", "x".repeat(StorageBlockCache.BLOCK_SIZE))
            },
        )
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        cache.openInputStream(entry).use { input ->
            assertEquals('x'.code, input.read())
            val block = temporary.toFile().walkTopDown().single { it.extension == "block" }
            block.writeBytes(ByteArray(StorageBlockCache.BLOCK_SIZE))
            assertArrayEquals(
                ByteArray(8192) {
                    'x'.code.toByte()
                },
                ByteArray(8192).also { assertEquals(it.size, input.read(it)) },
            )
            assertEquals(1, backend.reads.get())
        }
        cache.openInputStream(entry).use { assertEquals('x'.code, it.read()) }
        assertEquals(2, backend.reads.get())
    }

    @Test fun `invalidated complete stream refuses reads and skip after opening`() = runBlocking {
        var active = true
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", "image".repeat(100)) })
        val entry = backend.stat("page.jpg")
        val cache =
            StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { active }, backend) { entry.size }
        cache.openCompleteInputStream(entry).use { input ->
            assertEquals('i'.code, input.read())
            active = false
            assertThrows(CancellationException::class.java) { input.read() }
            assertThrows(CancellationException::class.java) { input.read(ByteArray(8)) }
            assertThrows(CancellationException::class.java) { input.read(ByteArray(8), 1, 2) }
            assertThrows(CancellationException::class.java) { input.skip(1) }
        }
        assertEquals(1, backend.copies.get())
        assertEquals(0, backend.reads.get())
    }

    @Test fun `invalidated ranged stream refuses reads from its buffered block`() = runBlocking {
        var active = true
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", "image".repeat(100)) })
        val entry = backend.stat("page.jpg")
        val cache =
            StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { active }, backend) { entry.size }
        cache.openInputStream(entry).use { input ->
            assertEquals('i'.code, input.read())
            assertEquals(1, backend.reads.get())
            active = false
            assertThrows(CancellationException::class.java) { input.read() }
            assertThrows(CancellationException::class.java) { input.read(ByteArray(8)) }
            assertThrows(CancellationException::class.java) { input.read(ByteArray(8), 1, 2) }
            assertThrows(CancellationException::class.java) { input.skip(1) }
        }
        assertEquals(1, backend.reads.get())
        assertEquals(0, backend.copies.get())
    }

    @Test fun `ranged stream skip defers transfer and reads from the skipped block`() = runBlocking {
        val content = "a".repeat(StorageBlockCache.BLOCK_SIZE) + "b".repeat(StorageBlockCache.BLOCK_SIZE)
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", content) })
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) {
            entry.size
        }

        cache.openInputStream(entry).use { input ->
            assertEquals(StorageBlockCache.BLOCK_SIZE.toLong(), input.skip(StorageBlockCache.BLOCK_SIZE.toLong()))
            assertEquals(0, backend.reads.get())
            assertEquals('b'.code, input.read())
        }

        assertEquals(1, backend.reads.get())
        assertEquals(StorageBlockCache.BLOCK_SIZE.toLong(), backend.rangeOffsets.single())
    }

    @Test fun `complete stream close is idempotent and releases its lease`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("page.jpg", "image") }
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        val input = cache.openCompleteInputStream(entry)
        val index = StorageCacheIndex.get(temporary.toFile())
        try {
            assertEquals(0, index.access { clearUnused() })
        } finally {
            input.close()
        }
        input.close()
        assertThrows(IllegalStateException::class.java) { input.read() }
        assertThrows(IllegalStateException::class.java) { input.read(ByteArray(8)) }
        assertEquals(1, index.access { clearUnused() })
    }

    @Test fun `ranged stream returns EOF without a transfer after the final byte`() = runBlocking {
        val backend = CountingStorageBackend(MemoryStorageBackend().apply { seed("page.jpg", "image") })
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) {
            entry.size
        }

        cache.openInputStream(entry).use { input ->
            assertEquals("image".length, input.readBytes().size)
            assertEquals(-1, input.read())
            assertEquals(-1, input.read(ByteArray(8)))
        }
        assertEquals(1, backend.reads.get())
    }

    @Test fun `ranged stream close is idempotent and releases its buffered block lease`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("page.jpg", "image") }
        val entry = backend.stat("page.jpg")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        val input = cache.openInputStream(entry)
        val index = StorageCacheIndex.get(temporary.toFile())
        try {
            assertEquals('i'.code, input.read())
            assertEquals(0, index.access { clearUnused() })
        } finally {
            input.close()
        }
        input.close()
        assertThrows(IllegalStateException::class.java) { input.read() }
        assertThrows(IllegalStateException::class.java) { input.read(ByteArray(8)) }
        assertEquals(1, index.access { clearUnused() })
    }

    @Test fun `complete streams isolate matching paths and versions by account and root`() = runBlocking {
        val sessions = listOf(
            StorageSession(1, "first", "root") { true },
            StorageSession(1, "second", "root") { true },
            StorageSession(1, "first", "other-root") { true },
        )
        val caches = sessions.mapIndexed { index, session ->
            val backend = CountingStorageBackend(
                MemoryStorageBackend().apply { seed("page.jpg", "image-$index") },
            )
            val entry = backend.stat("page.jpg")
            val cache = StorageBlockCache(temporary.toFile(), session, backend) { 1024 }
            cache.openCompleteInputStream(entry).use {
                assertArrayEquals("image-$index".encodeToByteArray(), it.readBytes())
            }
            Triple(cache, entry, backend)
        }
        caches.forEachIndexed { index, (cache, entry, backend) ->
            cache.openInputStream(entry, preferComplete = true).use {
                assertArrayEquals("image-$index".encodeToByteArray(), it.readBytes())
            }
            assertEquals(1, backend.copies.get())
            assertEquals(0, backend.reads.get())
        }
        assertEquals(3, temporary.toFile().walkTopDown().count { it.name == "complete.bin" })
    }

    @Test fun `obsolete session cannot serve or populate cache`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("book", "data") }
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { false }, backend) { 1024 }
        assertThrows(CancellationException::class.java) { runBlocking { cache.read(backend.stat("book"), 0, 4) } }
        assertEquals(0, temporary.toFile().walkTopDown().count { it.isFile })
    }

    @Test fun `changed version at the same path invalidates ranged and complete cache entries`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("page.jpg", "oldold") }
        val cache = StorageBlockCache(
            temporary.toFile(),
            StorageSession(1, "a", "r") { true },
            backend,
        ) { 1024 }
        val original = backend.stat("page.jpg")
        assertArrayEquals("oldold".encodeToByteArray(), cache.read(original, 0, 6))
        cache.openCompleteInputStream(original).use { assertArrayEquals("oldold".encodeToByteArray(), it.readBytes()) }

        backend.seed("page.jpg", "newnew")
        val replacement = backend.stat("page.jpg")

        assertArrayEquals("newnew".encodeToByteArray(), cache.read(replacement, 0, 6))
        cache.openCompleteInputStream(replacement).use {
            assertArrayEquals("newnew".encodeToByteArray(), it.readBytes())
        }
    }

    @Test fun `stable resource identity reuses cached bytes after a move`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("old", "cached") }
        val cache = StorageBlockCache(
            temporary.toFile(),
            StorageSession(1, "a", "r") { true },
            backend,
            resourceIdentity = { "stable-resource" },
        ) { 1024 }
        val original = backend.stat("old")
        assertArrayEquals("cached".encodeToByteArray(), cache.read(original, 0, 6))
        backend.move(original, "new")
        val moved = backend.stat("new")
        backend.failReads = true
        assertArrayEquals("cached".encodeToByteArray(), cache.read(moved, 0, 6))
        assertEquals(1, temporary.toFile().walkTopDown().count { it.extension == "block" })
    }

    @Test fun `reused paths with matching versions cannot serve a moved files cached bytes`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("first.cbz", "oldone") }
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun stat(path: String) = memory.stat(path).copy(version = "same-version")
            override suspend fun list(path: String) = memory.list(path).map { it.copy(version = "same-version") }
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int) =
                memory.read(memory.stat(entry.path), offset, length)
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) =
                memory.copyTo(memory.stat(entry.path), output)
            override suspend fun move(entry: StorageEntry, destination: String) =
                memory.move(memory.stat(entry.path), destination)
        }
        val session = StorageSession(1, "account", "root") { true }
        val records = StorageRecordStore(session, MemoryStorageRepository(), Json)
        val identities = StorageResourceIdentities(backend, records)
        val cache = StorageBlockCache(
            temporary.toFile(),
            session,
            backend,
            resourceIdentity = identities::identity,
        ) { 1024 }
        val original = backend.stat("first.cbz")
        assertArrayEquals("oldone".encodeToByteArray(), cache.read(original, 0, 6))
        StorageMutations(backend, records, identities).move(original, "second.cbz")
        memory.seed("first.cbz", "newone")
        val replacement = backend.stat("first.cbz")
        val moved = backend.stat("second.cbz")
        assertArrayEquals("newone".encodeToByteArray(), cache.read(replacement, 0, 6))
        memory.failReads = true
        assertArrayEquals("oldone".encodeToByteArray(), cache.read(moved, 0, 6))
        assertArrayEquals("newone".encodeToByteArray(), cache.read(replacement, 0, 6))
    }

    private class CountingStorageBackend(private val memory: MemoryStorageBackend) : LibraryStorageBackend by memory {
        val reads = AtomicInteger()
        val copies = AtomicInteger()
        val transferredBytes = AtomicLong()
        val rangeOffsets = mutableListOf<Long>()

        override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
            reads.incrementAndGet()
            rangeOffsets += offset
            return memory.read(entry, offset, length).also { transferredBytes.addAndGet(it.size.toLong()) }
        }

        override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
            copies.incrementAndGet()
            memory.copyTo(
                entry,
                object : OutputStream() {
                    override fun write(value: Int) {
                        output.write(value)
                        transferredBytes.incrementAndGet()
                    }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        output.write(buffer, offset, length)
                        transferredBytes.addAndGet(length.toLong())
                    }
                },
            )
        }
    }
}
