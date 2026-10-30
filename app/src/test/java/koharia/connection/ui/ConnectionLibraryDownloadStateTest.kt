package koharia.connection.ui

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.every
import io.mockk.mockk
import koharia.connection.MangaDownloadStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class ConnectionLibraryDownloadStateTest {
    @Test
    fun `aggregate download state is local and does not claim completeness without total`() {
        val firstOwner = Manga.create().copy(id = 7, source = 41, title = "Shared title")
        val secondOwner = Manga.create().copy(id = 7, source = 42, title = "Shared title")
        val downloads = mockk<DownloadManager> {
            every { getDownloadCount(any()) } answers {
                if (firstArg<Manga>().source == 41L) 3 else 1
            }
        }

        assertEquals(
            MangaDownloadStatus.UNKNOWN_TOTAL,
            aggregateDownloadState(firstOwner, downloads).status,
        )
        assertEquals(3L, aggregateDownloadState(firstOwner, downloads).count)
        assertEquals(1L, aggregateDownloadState(secondOwner, downloads).count)
    }
}
