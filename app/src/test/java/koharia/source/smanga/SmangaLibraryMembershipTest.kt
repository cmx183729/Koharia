package koharia.source.smanga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class SmangaLibraryMembershipTest {
    private fun manga(url: String, source: Long = 7L): Manga = Manga.create().copy(source = source, url = url)

    @Test
    fun `membership requires current connection account and a positive manga id`() {
        assertTrue(manga("/smanga/7/account/manga/12").matchesSmangaLibraryMembership(7, "account"))
        assertFalse(manga("/smanga/7/other/manga/12").matchesSmangaLibraryMembership(7, "account"))
        assertFalse(manga("/smanga/8/account/manga/12").matchesSmangaLibraryMembership(7, "account"))
        assertFalse(manga("/smanga/7/account/manga/12", source = 8).matchesSmangaLibraryMembership(7, "account"))
        assertFalse(manga("/smanga/7/account/manga/0").matchesSmangaLibraryMembership(7, "account"))
        assertFalse(manga("/smanga/7/account/manga/not-a-number").matchesSmangaLibraryMembership(7, "account"))
    }

    @Test
    fun `membership does not depend on shelf metadata or selected media`() {
        assertTrue(manga("/smanga/7/account/manga/12").matchesSmangaLibraryMembership(7, "account"))
    }
}
