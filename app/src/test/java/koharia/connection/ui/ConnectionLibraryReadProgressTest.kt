package koharia.connection.ui

import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class ConnectionLibraryReadProgressTest {

    @Test
    fun `progress remains isolated when owners share a manga id`() {
        val first = libraryEntry(source = 41L, id = 7L, total = 10, read = 3)
        val second = libraryEntry(source = 42L, id = 7L, total = 20, read = 15)

        val progress = connectionLibraryReadProgress(listOf(first, second))

        assertEquals(2, progress.size)
        assertEquals(
            3L to 10L,
            progress[ConnectionLibraryEntryKey(41L, 7L)]?.let {
                it.readCount to it.totalChapterCount
            },
        )
        assertEquals(
            15L to 20L,
            progress[ConnectionLibraryEntryKey(42L, 7L)]?.let {
                it.readCount to
                    it.totalChapterCount
            },
        )
    }

    private fun libraryEntry(source: Long, id: Long, total: Long, read: Long): ConnectionLibraryEntry {
        val manga = Manga.create().copy(id = id, source = source)
        val libraryManga = LibraryManga(
            manga = manga,
            categories = emptyList(),
            totalChapters = total,
            readCount = read,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
        return ConnectionLibraryEntry(manga, libraryManga)
    }
}
