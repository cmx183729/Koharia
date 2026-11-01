package koharia.source.kavita

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class KavitaLibraryMembershipTest {
    @Test
    fun `membership requires current account prefix and selected library`() {
        val entry = Manga.create().copy(
            url = "/kavita/42/alice/series/7",
            memo = buildJsonObject { put("kavitaLibraryId", 3L) },
        )

        assertTrue(entry.matchesKavitaLibraryMembership(42L, "alice", 3L))
        assertFalse(entry.matchesKavitaLibraryMembership(42L, "alice", 4L))
        assertFalse(entry.matchesKavitaLibraryMembership(42L, "bob", 3L))
        assertTrue(entry.matchesKavitaLibraryMembership(42L, "alice", 0L))
    }

    @Test
    fun `all selected libraries accept current-account rows`() {
        val entry = Manga.create().copy(
            url = "/kavita/42/alice/series/7",
            memo = buildJsonObject { put("kavitaLibraryId", 3L) },
        )

        assertTrue(entry.matchesKavitaLibraryMembership(42L, "alice", 0L))
    }
}
