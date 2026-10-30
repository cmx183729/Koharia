package koharia.connection.ui

import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class ConnectionLibraryReadProgressTest {

    @Test
    fun `missing or nonpositive stored totals do not create progress`() {
        val missingStats = ConnectionLibraryEntry(Manga.create().copy(id = 1L, source = 41L))
        val zeroTotal = libraryEntry(source = 41L, id = 2L, total = 0, read = 0)
        val negativeTotal = libraryEntry(source = 41L, id = 3L, total = -1, read = 4)

        val progress = connectionLibraryReadProgress(listOf(missingStats, zeroTotal, negativeTotal))

        assertEquals(mapOf(missingStats.key to null, zeroTotal.key to null, negativeTotal.key to null), progress)
    }

    @Test
    fun `progress clamps read count to stored total`() {
        val entry = libraryEntry(source = 41L, id = 4L, total = 5, read = 8)

        val progress = connectionLibraryReadProgress(listOf(entry))

        assertEquals(5L to 5L, progress[entry.key]?.let { it.readCount to it.totalChapterCount })
    }

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
