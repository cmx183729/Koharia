package koharia.source.smanga

import tachiyomi.domain.manga.model.Manga

/**
 * Narrows persisted Smanga rows to the current connection/account without network access. The
 * adapter is also used by backup and cache cleanup, so it deliberately does not apply the shelf's
 * selected-media preference; rows from every current-account media remain retained.
 */
internal fun Manga.matchesSmangaLibraryMembership(
    connectionId: Long,
    accountKey: String,
): Boolean {
    if (source != connectionId) return false
    val prefix = "/smanga/$connectionId/$accountKey/manga/"
    if (!url.startsWith(prefix)) return false
    return url.removePrefix(prefix).toLongOrNull()?.let { it > 0 } == true
}
