package koharia.source.local

import koharia.connection.LibraryConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLibraryInitialSetupTest {
    private val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
    private val local = LibraryConnectionProfile(1, LocalFolderConnectionProvider.ID, "Local")

    @Test
    fun `new library draft defaults to comics while saved configurations keep their enabled types`() {
        val draft = LocalLibraryConfig().withInitialBookshelves("Comics", "Books").initialSetupDraft(isNew = true)
        assertEquals(setOf(LocalLibraryContentType.COMICS), draft.enabledContentTypes)
        assertTrue(draft.canUseSelectedDirectory())
        assertEquals(
            listOf(LocalLibraryContentType.COMICS),
            draft.enabledLibraryConfiguration().bookshelves.map { it.contentType },
        )
        assertEquals(config, config.initialSetupDraft(isNew = false))
        val completed = config.copy(setupCompleted = true)
        assertEquals(completed, completed.initialSetupDraft(isNew = true))
        val existing = config.copy(roots = listOf(LocalLibraryRootConfig(id = "keep")))
        assertEquals(existing, existing.initialSetupDraft(isNew = true))
    }

    @Test
    fun `first local connection creates directories even when network connections already exist`() {
        assertTrue(shouldCreateInitialLocalDirectories(1, config, listOf(local)))
        assertTrue(
            shouldCreateInitialLocalDirectories(
                1,
                config,
                listOf(LibraryConnectionProfile(2, "komga", "Server"), local),
            ),
        )
    }

    @Test
    fun `later or already configured local connections are untouched`() {
        assertFalse(shouldCreateInitialLocalDirectories(2, config, listOf(local, local.copy(id = 2))))
        assertFalse(shouldCreateInitialLocalDirectories(1, config.copy(setupCompleted = true), listOf(local)))
        assertFalse(
            shouldCreateInitialLocalDirectories(
                1,
                config.copy(roots = listOf(LocalLibraryRootConfig(id = "old"))),
                listOf(local),
            ),
        )
    }

    @Test
    fun `both default libraries require an explicit organization choice`() {
        val comics = config.defaultBookshelfId(LocalLibraryContentType.COMICS)
        val books = config.defaultBookshelfId(LocalLibraryContentType.BOOKS)
        assertFalse(config.hasSelectedDefaultModes(emptyList()))
        assertFalse(config.hasSelectedDefaultModes(listOf(comics)))
        assertFalse(config.hasSelectedDefaultModes(listOf(books)))
        assertFalse(config.hasSelectedDefaultModes(listOf("other", comics)))
        assertTrue(config.hasSelectedDefaultModes(listOf(comics, books)))
        assertTrue(config.copy(setupCompleted = true).hasSelectedDefaultModes(emptyList()))
    }

    @Test
    fun `local setup reads selected directory by default for either single content type`() {
        val context = io.mockk.mockk<android.content.Context>()
        for (type in listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)) {
            val input = config.copy(enabledContentTypes = setOf(type))
            val result = prepareInitialLocalDirectories(
                context,
                input,
                "content://selected",
                "Selected",
                kotlinx.serialization.json.Json,
            )
            val root = result.roots.single()
            assertEquals("content://selected", root.treeUri)
            assertEquals("", root.relativePath)
            assertEquals(type, root.contentType)
            assertFalse(root.managed)
            assertEquals(
                result,
                prepareInitialLocalDirectories(
                    context,
                    result,
                    "content://different",
                    "Different",
                    kotlinx.serialization.json.Json,
                ),
            )
        }
        io.mockk.verify { context wasNot io.mockk.Called }
    }

    @Test
    fun `a directly selected root can be represented without a managed subdirectory`() {
        val result = config.copy(
            enabledContentTypes = setOf(LocalLibraryContentType.COMICS),
        ).withDirectlySelectedRoot(
            treeUri = "content://selected",
            displayPath = "Selected",
            contentType = LocalLibraryContentType.COMICS,
        )
        val root = result.roots.single()
        assertEquals("", root.relativePath)
        assertFalse(root.managed)
        assertEquals("content://selected", root.treeUri)
        assertEquals(LocalLibraryContentType.COMICS, root.contentType)
        assertEquals(result.defaultBookshelfId(LocalLibraryContentType.COMICS), root.bookshelfId)
    }

    @Test
    fun `direct root selection rejects a shared root for multiple content types`() {
        assertThrows(IllegalArgumentException::class.java) {
            config.withDirectlySelectedRoot(
                treeUri = "content://selected",
                displayPath = "Selected",
                contentType = LocalLibraryContentType.COMICS,
            )
        }
    }

    @Test
    fun `only enabled libraries require a mode and both disabled cannot continue`() {
        val comics = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS))
        val books = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.BOOKS))
        assertTrue(comics.hasSelectedDefaultModes(listOf(comics.defaultBookshelfId(LocalLibraryContentType.COMICS))))
        assertTrue(books.hasSelectedDefaultModes(listOf(books.defaultBookshelfId(LocalLibraryContentType.BOOKS))))
        assertFalse(books.hasSelectedDefaultModes(listOf(config.defaultBookshelfId(LocalLibraryContentType.COMICS))))
        val none = config.copy(enabledContentTypes = emptySet())
        assertFalse(none.hasSelectedDefaultModes(config.bookshelves.map { it.id }))
        assertThrows(IllegalArgumentException::class.java) { none.enabledLibraryConfiguration() }
    }

    @Test
    fun `disabled draft libraries and roots are not revived when saved configuration migrates`() {
        val draft = config.copy(
            enabledContentTypes = setOf(LocalLibraryContentType.BOOKS),
            roots = listOf(
                LocalLibraryRootConfig(id = "comics", contentType = LocalLibraryContentType.COMICS),
                LocalLibraryRootConfig(id = "books", contentType = LocalLibraryContentType.BOOKS),
            ),
        )
        val saved = draft.enabledLibraryConfiguration().migrate(1)
        assertEquals(setOf(LocalLibraryContentType.BOOKS), saved.enabledContentTypes)
        assertEquals(listOf(LocalLibraryContentType.BOOKS), saved.bookshelves.map { it.contentType })
        assertEquals(listOf("books"), saved.roots.map { it.id })
        assertEquals(2, draft.bookshelves.size)
        assertEquals(2, draft.roots.size)
    }
}
