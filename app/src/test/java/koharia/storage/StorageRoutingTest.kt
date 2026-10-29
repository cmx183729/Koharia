package koharia.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StorageRoutingTest {
    @Test fun `LAN network failure falls back but authentication root and protocol errors never do`() = runBlocking {
        val primary = MemoryStorageBackend().apply { seed("book", "primary") }
        val internal = MemoryStorageBackend().apply { seed("book", "internal") }
        for (reason in listOf(
            StorageFailure.Reason.AUTH,
            StorageFailure.Reason.UNVERIFIED,
            StorageFailure.Reason.PROTOCOL,
        )) {
            val router = StorageEndpointRouter(primary, internal, { "wifi" }, { backend ->
                if (backend === internal) throw StorageFailure(reason)
            })
            val error = assertThrows(StorageFailure::class.java) { runBlocking { router.stat("book") } }
            assertEquals(reason, error.reason)
        }
        val router = StorageEndpointRouter(primary, internal, { "wifi" }, { backend ->
            if (backend === internal) throw StorageFailure(StorageFailure.Reason.NETWORK)
        })
        val entry = router.stat("book")
        assertEquals("primary", router.read(entry, 0, entry.size.toInt()).decodeToString())
    }

    @Test fun `combined directory listing retries only verified network failures`() = runBlocking {
        val requests = mutableListOf<String>()
        val primary = object : LibraryStorageBackend by MemoryStorageBackend() {
            override suspend fun listDirectory(path: String): StorageDirectoryListing {
                requests += "primary"
                return StorageDirectoryListing(StorageEntry(path, true), emptyList())
            }
        }
        var failure = StorageFailure.Reason.NETWORK
        val internal = object : LibraryStorageBackend by MemoryStorageBackend() {
            override suspend fun listDirectory(path: String): StorageDirectoryListing {
                requests += "internal"
                throw StorageFailure(failure)
            }
        }
        val router = StorageEndpointRouter(primary, internal, { "wifi" }, {})
        assertEquals(true, router.listDirectory("")?.directory?.directory)
        assertEquals(listOf("internal", "primary"), requests)
        requests.clear()
        failure = StorageFailure.Reason.AUTH
        val restricted = StorageEndpointRouter(primary, internal, { "wifi" }, {})
        assertThrows(StorageFailure::class.java) { runBlocking { restricted.listDirectory("") } }
        assertEquals(listOf("internal"), requests)
    }

    @Test fun `combined listing rejects results from an obsolete session`() = runBlocking {
        var active = true
        var calls = 0
        val backend = object : LibraryStorageBackend by MemoryStorageBackend() {
            override suspend fun listDirectory(path: String): StorageDirectoryListing {
                calls++
                active = false
                return StorageDirectoryListing(StorageEntry(path, true), emptyList())
            }
        }
        val session = SessionStorageBackend(backend, StorageSession(1, "fixture", "root") { active })
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { session.listDirectory("") }
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { session.listDirectory("") }
        }
        assertEquals(1, calls)
    }

    @Test fun `lost mutation response is never replayed on primary`() = runBlocking {
        val primary = MemoryStorageBackend()
        val internal = MemoryStorageBackend().apply { failAfterMutation = 1 }
        val router = StorageEndpointRouter(primary, internal, { "wifi" }, {})
        assertThrows(StorageFailure::class.java) {
            runBlocking { router.write("book", "content".byteInputStream(), 7) }
        }
        assertEquals(0, primary.uploads)
        assertEquals(1, internal.uploads)
    }
}
