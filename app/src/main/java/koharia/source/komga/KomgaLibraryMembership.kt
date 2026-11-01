package koharia.source.komga

import koharia.komga.api.dto.KOMGA_LIBRARY_IDS_MEMO_KEY
import koharia.komga.api.dto.KOMGA_LIBRARY_ID_MEMO_KEY
import koharia.komga.api.dto.KOMGA_LIBRARY_NAMESPACE_MEMO_KEY
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.manga.model.Manga

/**
 * Matches only catalogue rows whose cached Komga response identified a server library.
 * Unknown rows are intentionally excluded because this adapter must not turn the full
 * persisted Komga catalogue into the aggregate library without membership evidence.
 */
internal fun Manga.matchesKomgaLibraryMembership(
    selectedLibraryIds: Set<String>,
    currentNamespace: String? = null,
): Boolean = matchesKomgaLibraryMembership(
    cachedLibraryIds = cachedKomgaLibraryIds(),
    selectedLibraryIds = selectedLibraryIds,
    cachedNamespace = memo[KOMGA_LIBRARY_NAMESPACE_MEMO_KEY].contentOrNullOrNull(),
    currentNamespace = currentNamespace,
)

internal fun matchesKomgaLibraryMembership(
    cachedLibraryIds: Set<String>,
    selectedLibraryIds: Set<String>,
    cachedNamespace: String? = null,
    currentNamespace: String? = null,
): Boolean = cachedLibraryIds.isNotEmpty() &&
    (currentNamespace == null || cachedNamespace == currentNamespace) &&
    (selectedLibraryIds.isEmpty() || cachedLibraryIds.any(selectedLibraryIds::contains))

internal fun Manga.cachedKomgaLibraryIds(): Set<String> = buildSet {
    memo[KOMGA_LIBRARY_ID_MEMO_KEY].contentOrNullOrNull()?.takeIf(String::isNotBlank)?.let(::add)
    (memo[KOMGA_LIBRARY_IDS_MEMO_KEY] as? JsonArray)
        .orEmpty()
        .mapNotNullTo(this) { it.contentOrNullOrNull()?.takeIf(String::isNotBlank) }
}

private fun JsonElement?.contentOrNullOrNull(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()
