package koharia.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StorageDirectoryListingTest {
    @Test
    fun `combined listing failure preserves the previous complete snapshot without separate retries`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("1.jpg", "fixture") }
        var failure = false
        var combinedCalls = 0
        var separateCalls = 0
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun listDirectory(path: String): StorageDirectoryListing {
                combinedCalls++
                if (failure) throw StorageFailure(StorageFailure.Reason.NETWORK)
                return StorageDirectoryListing(memory.stat(path), memory.list(path))
            }
            override suspend fun stat(path: String): StorageEntry {
                separateCalls++
                return memory.stat(path)
            }
            override suspend fun list(path: String): List<StorageEntry> {
                separateCalls++
                return memory.list(path)
            }
        }
        val records = records()
        val snapshot = StorageSnapshot(backend, records)
        val saved = snapshot.directory("")
        failure = true

        assertThrows(StorageFailure::class.java) { runBlocking { snapshot.directory("", refresh = true) } }
        assertEquals(saved, StorageSnapshot(backend, records).directory(""))
        assertEquals(2, combinedCalls)
        assertEquals(0, separateCalls)
    }

    @Test
    fun `backends without combined metadata retain one stat and one list per cold directory`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("1.jpg", "fixture") }
        var statCalls = 0
        var listCalls = 0
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun stat(path: String): StorageEntry {
                statCalls++
                return memory.stat(path)
            }
            override suspend fun list(path: String): List<StorageEntry> {
                listCalls++
                return memory.list(path)
            }
        }
        val snapshot = StorageSnapshot(backend, records())
        val first = snapshot.directory("")
        assertEquals(listOf("1.jpg"), first.children.map { it.path })
        assertEquals(first, snapshot.directory(""))
        assertEquals(1, statCalls)
        assertEquals(1, listCalls)
    }

    @Test
    fun `invalid combined directory identity or children never replace a valid snapshot`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("1.jpg", "fixture") }
        val records = records()
        val saved = StorageSnapshot(memory, records).directory("")
        val invalid = listOf(
            StorageDirectoryListing(StorageEntry("other", true), emptyList()),
            StorageDirectoryListing(StorageEntry("", false), emptyList()),
            StorageDirectoryListing(StorageEntry("", true), listOf(StorageEntry("nested/1.jpg", false))),
            StorageDirectoryListing(StorageEntry("", true), listOf(StorageEntry("", true))),
        )
        for (listing in invalid) {
            val backend = object : LibraryStorageBackend by memory {
                override suspend fun listDirectory(path: String) = listing
            }
            assertThrows(Exception::class.java) {
                runBlocking { StorageSnapshot(backend, records).directory("", refresh = true) }
            }
            assertEquals(saved, StorageSnapshot(memory, records).directory(""))
        }
    }

    @Test
    fun `cancelled combined listing is not persisted as an empty success`() = runBlocking {
        val memory = MemoryStorageBackend()
        val records = records()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun listDirectory(path: String): StorageDirectoryListing =
                throw CancellationException("fixture cancellation")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { StorageSnapshot(backend, records).directory("") }
        }
        assertEquals(emptyList<Any>(), records.list("directories"))
    }

    private fun records() = StorageRecordStore(
        StorageSession(1, "fixture", "root") { true },
        MemoryStorageRepository(),
        Json,
    )
}
