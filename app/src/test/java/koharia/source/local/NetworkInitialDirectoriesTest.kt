package koharia.source.local

import koharia.storage.MemoryStorageBackend
import koharia.storage.StorageFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkInitialDirectoriesTest {
    private val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")

    @Test fun `selected defaults are created linked and reusable`() = runBlocking {
        val backend = MemoryStorageBackend()
        val prepared = prepareInitialNetworkDirectories(backend, config)
        assertEquals(setOf("Comics", "Books"), prepared.roots.map { it.relativePath }.toSet())
        prepared.roots.forEach { assertEquals(config.defaultBookshelfId(it.contentType), it.bookshelfId) }
        assertEquals(prepared, prepareInitialNetworkDirectories(backend, prepared))
        assertEquals(setOf("Comics", "Books"), backend.createdDirectories)
    }

    @Test fun `single content type reads the selected root without creating directories`() = runBlocking {
        for (type in listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)) {
            val backend = MemoryStorageBackend().apply { seed("existing.jpg", "preserve") }
            val input = config.copy(enabledContentTypes = setOf(type))
            val prepared = prepareInitialNetworkDirectories(backend, input)
            val root = prepared.roots.single()
            assertEquals("", root.relativePath)
            assertEquals(false, root.managed)
            assertEquals(type, root.contentType)
            assertEquals(input.defaultBookshelfId(type), root.bookshelfId)
            assertEquals(prepared, prepareInitialNetworkDirectories(backend, prepared))
            assertTrue(backend.createdDirectories.isEmpty())
            assertEquals(0, backend.uploads)
            assertEquals("preserve", backend.contents.getValue("existing.jpg").decodeToString())
        }
    }

    @Test fun `managed subdirectories remain an explicit single type alternative`() = runBlocking {
        val backend = MemoryStorageBackend()
        val prepared = prepareInitialNetworkDirectories(
            backend,
            config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS)),
            useSelectedDirectory = false,
        )
        assertEquals("Comics", prepared.roots.single().relativePath)
        assertTrue(prepared.roots.single().managed)
        assertEquals(setOf("Comics"), backend.createdDirectories)
    }

    @Test fun `direct network root checks readability and does not swallow failures`() = runBlocking {
        val backend = MemoryStorageBackend().apply { failReads = true }
        val input = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS))
        assertThrows(StorageFailure::class.java) { runBlocking { prepareInitialNetworkDirectories(backend, input) } }
        assertTrue(backend.createdDirectories.isEmpty())
    }

    @Test fun `direct network setup refuses two content types on one root`() = runBlocking {
        val backend = MemoryStorageBackend()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { prepareInitialNetworkDirectories(backend, config, useSelectedDirectory = true) }
        }
        assertTrue(backend.createdDirectories.isEmpty())
    }

    @Test fun `existing managed network root identity and path are never rewritten by new defaults`() = runBlocking {
        val backend = MemoryStorageBackend()
        val managed = LocalLibraryRootConfig(
            id = "keep-root",
            relativePath = "Comics",
            managed = true,
            contentType = LocalLibraryContentType.COMICS,
        )
        val input = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS), roots = listOf(managed))
        assertEquals(input, prepareInitialNetworkDirectories(backend, input))
        assertTrue(backend.createdDirectories.isEmpty())
    }

    @Test fun `partly configured dual types use separate children`() = runBlocking {
        val backend = MemoryStorageBackend()
        val manual = LocalLibraryRootConfig(
            id = "keep",
            contentType = LocalLibraryContentType.COMICS,
            relativePath = "Comics",
            bookshelfId = config.defaultBookshelfId(LocalLibraryContentType.COMICS),
        )
        val input = config.copy(roots = listOf(manual))
        val prepared = prepareInitialNetworkDirectories(backend, input)
        assertEquals(manual, prepared.roots.first())
        assertEquals("Books", prepared.roots.last().relativePath)
        assertTrue(prepared.roots.last().managed)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { prepareInitialNetworkDirectories(backend, input, useSelectedDirectory = true) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            input.withDirectlySelectedRoot("content://selected", "Selected", LocalLibraryContentType.BOOKS)
        }
    }

    @Test fun `unchecked types and manually assigned roots are preserved`() = runBlocking {
        val backend = MemoryStorageBackend()
        val manual =
            LocalLibraryRootConfig(
                id = "manual",
                relativePath = "existing",
                contentType = LocalLibraryContentType.COMICS,
            )
        val input = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS), roots = listOf(manual))
        assertEquals(input, prepareInitialNetworkDirectories(backend, input))
        assertTrue(backend.createdDirectories.isEmpty())
    }

    @Test fun `same name file is not overwritten and completed setup is untouched`() = runBlocking {
        val backend = MemoryStorageBackend()
        backend.seed("Comics", "preserve")
        assertThrows(StorageFailure::class.java) { runBlocking { prepareInitialNetworkDirectories(backend, config) } }
        assertEquals("preserve", backend.contents.getValue("Comics").decodeToString())
        val completed = config.copy(setupCompleted = true)
        assertEquals(completed, prepareInitialNetworkDirectories(backend, completed))
    }

    @Test fun `retry reuses directories after uncertain mutation without duplication`() = runBlocking {
        val backend = MemoryStorageBackend().apply { failAfterMutation = 1 }
        assertThrows(StorageFailure::class.java) { runBlocking { prepareInitialNetworkDirectories(backend, config) } }
        backend.failAfterMutation = null
        val prepared = prepareInitialNetworkDirectories(backend, config)
        assertEquals(2, prepared.roots.size)
        assertEquals(setOf("Comics", "Books"), backend.createdDirectories)
    }
}
