package koharia.source.komga

import koharia.komga.api.dto.KOMGA_LIBRARY_IDS_MEMO_KEY
import koharia.komga.api.dto.KOMGA_LIBRARY_ID_MEMO_KEY
import koharia.komga.api.dto.KOMGA_LIBRARY_NAMESPACE_MEMO_KEY
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class KomgaLibraryMembershipTest {

    @Test
    fun `matching cached library is included`() {
        assertTrue(
            matchesKomgaLibraryMembership(
                cachedLibraryIds = setOf("comic-library"),
                selectedLibraryIds = setOf("comic-library"),
            ),
        )
    }

    @Test
    fun `nonmatching and unknown cached libraries are excluded`() {
        assertFalse(
            matchesKomgaLibraryMembership(
                cachedLibraryIds = setOf("other-library"),
                selectedLibraryIds = setOf("comic-library"),
            ),
        )
        assertFalse(
            matchesKomgaLibraryMembership(
                cachedLibraryIds = emptySet(),
                selectedLibraryIds = emptySet(),
            ),
        )
    }

    @Test
    fun `empty configured selection accepts any known cached library`() {
        assertTrue(
            matchesKomgaLibraryMembership(
                cachedLibraryIds = setOf("comic-library", "book-library"),
                selectedLibraryIds = emptySet(),
            ),
        )
    }

    @Test
    fun `old endpoint namespace is excluded even when library id matches`() {
        assertFalse(
            matchesKomgaLibraryMembership(
                cachedLibraryIds = setOf("comic-library"),
                selectedLibraryIds = setOf("comic-library"),
                cachedNamespace = "old-endpoint",
                currentNamespace = "new-endpoint",
            ),
        )
        assertTrue(
            matchesKomgaLibraryMembership(
                cachedLibraryIds = setOf("comic-library"),
                selectedLibraryIds = setOf("comic-library"),
                cachedNamespace = "new-endpoint",
                currentNamespace = "new-endpoint",
            ),
        )
    }

    @Test
    fun `namespace is read from manga memo`() {
        val manga = Manga.create().copy(
            memo = buildJsonObject {
                put(KOMGA_LIBRARY_ID_MEMO_KEY, "comic-library")
                put(KOMGA_LIBRARY_NAMESPACE_MEMO_KEY, "new-endpoint")
            },
        )

        assertTrue(manga.matchesKomgaLibraryMembership(setOf("comic-library"), "new-endpoint"))
        assertFalse(manga.matchesKomgaLibraryMembership(setOf("comic-library"), "old-endpoint"))
    }

    @Test
    fun `manga memo combines singular and plural cached library ids`() {
        val manga = Manga.create().copy(
            memo = buildJsonObject {
                put(KOMGA_LIBRARY_ID_MEMO_KEY, "comic-library")
                put(
                    KOMGA_LIBRARY_IDS_MEMO_KEY,
                    JsonArray(listOf(JsonPrimitive("comic-library"), JsonPrimitive("book-library"))),
                )
            },
        )

        assertTrue(manga.matchesKomgaLibraryMembership(setOf("book-library")))
        assertFalse(manga.matchesKomgaLibraryMembership(setOf("other-library")))
    }
}
