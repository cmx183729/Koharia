package koharia.connection.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class ConnectionLibraryNavigationTest {

    @Test
    fun `details route retains owning source and url`() {
        val manga = Manga.create().copy(id = 17L, source = 42L, url = "/series/17")

        val screen = manga.toConnectionDetailsScreen()

        assertEquals(manga.id, screen.mangaId)
        assertTrue(screen.fromSource)
        assertEquals(manga.source, screen.sourceId)
        assertEquals(manga.url, screen.mangaUrl)
    }
}
