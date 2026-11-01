package koharia.source.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class LanraragiLibraryMembershipTest {
    private fun manga(url: String, source: Long = 7L): Manga = Manga.create().copy(source = source, url = url)

    private val entries = listOf(
        LanraragiEntry(id = "shared", kind = LanraragiEntry.Kind.ARCHIVE, title = "Archive"),
        LanraragiEntry(id = "shared", kind = LanraragiEntry.Kind.TANK, title = "Tank"),
        LanraragiEntry(id = "category", kind = LanraragiEntry.Kind.CATEGORY, title = "Category"),
    )

    @Test
    fun `membership matches current snapshot by kind and id`() {
        assertTrue(manga("/lanraragi/7/archive/shared").matchesLanraragiLibraryMembership(7, entries))
        assertTrue(manga("/lanraragi/7/tank/shared").matchesLanraragiLibraryMembership(7, entries))
        assertTrue(manga("/lanraragi/7/category/category").matchesLanraragiLibraryMembership(7, entries))
        assertFalse(manga("/lanraragi/7/archive/missing").matchesLanraragiLibraryMembership(7, entries))
    }

    @Test
    fun `membership rejects foreign connection malformed url and empty snapshot`() {
        assertFalse(manga("/lanraragi/8/archive/shared").matchesLanraragiLibraryMembership(7, entries))
        assertFalse(manga("/lanraragi/7/unknown/shared").matchesLanraragiLibraryMembership(7, entries))
        assertFalse(manga("/lanraragi/7/archive/").matchesLanraragiLibraryMembership(7, entries))
        assertFalse(manga("/lanraragi/7/archive/shared/extra").matchesLanraragiLibraryMembership(7, entries))
        assertFalse(manga("/lanraragi/7/archive/shared").matchesLanraragiLibraryMembership(7, emptyList()))
        assertFalse(
            manga("/lanraragi/7/archive/shared", source = 8)
                .matchesLanraragiLibraryMembership(7, entries),
        )
    }
}
