package koharia.connection.ui

import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
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
    fun `managed entries derive progress from persisted chapters`() {
        val entry = ConnectionLibraryEntry(Manga.create().copy(id = 8L, source = 41L))
        val chapters = listOf(
            chapter(8L, 1L, read = true),
            chapter(8L, 2L, read = false),
        )

        val progress = connectionLibraryReadProgress(listOf(entry), mapOf(8L to chapters))

        assertEquals(1L to 2L, progress[entry.key]?.let { it.readCount to it.totalChapterCount })
    }

    @Test
    fun `chapter progress does not collapse owners sharing a manga id`() {
        val first = ConnectionLibraryEntry(Manga.create().copy(id = 9L, source = 41L))
        val second = ConnectionLibraryEntry(Manga.create().copy(id = 9L, source = 42L))
        val chapters = listOf(chapter(9L, 1L, read = true))

        val progress = connectionLibraryReadProgress(listOf(first, second), mapOf(9L to chapters))

        assertEquals(null, progress[first.key])
        assertEquals(null, progress[second.key])
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

    private fun chapter(mangaId: Long, id: Long, read: Boolean): Chapter = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        read = read,
    )

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
