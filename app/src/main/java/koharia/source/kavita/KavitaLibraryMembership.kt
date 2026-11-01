package koharia.source.kavita

import koharia.kavita.KavitaIdentity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tachiyomi.domain.manga.model.Manga

private const val KAVITA_LIBRARY_ID_MEMO_KEY = "kavitaLibraryId"

/**
 * Narrows persisted Kavita rows to the current account and selected shelf library. The account
 * prefix is local identity data; this never refreshes the remote catalogue.
 */
internal fun Manga.matchesKavitaLibraryMembership(
    connectionId: Long,
    accountKey: String,
    selectedLibraryId: Long,
): Boolean {
    val prefix = runCatching { KavitaIdentity(connectionId, accountKey).prefix }.getOrNull() ?: return false
    if (!url.startsWith(prefix)) return false
    if (selectedLibraryId == 0L) return true
    return memo[KAVITA_LIBRARY_ID_MEMO_KEY].asLongOrNull() == selectedLibraryId
}

private fun JsonElement?.asLongOrNull(): Long? = runCatching { this?.jsonPrimitive?.longOrNull }.getOrNull()
