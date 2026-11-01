package koharia.source.lanraragi

import koharia.domain.lanraragi.LanraragiEntry
import tachiyomi.domain.manga.model.Manga

internal fun Manga.matchesLanraragiLibraryMembership(
    connectionId: Long,
    entries: List<LanraragiEntry>,
): Boolean {
    val prefix = "/lanraragi/$connectionId/"
    if (source != connectionId || !url.startsWith(prefix)) return false

    val resource = url.removePrefix(prefix)
    val separator = resource.indexOf('/')
    if (separator <= 0 || separator == resource.lastIndex) return false

    val kind = resource.substring(0, separator).uppercase().let { value ->
        runCatching { LanraragiEntry.Kind.valueOf(value) }.getOrNull()
    } ?: return false
    val id = resource.substring(separator + 1)
    if (id.isEmpty() || id.contains('/')) return false

    return entries.any { it.kind == kind && it.id == id }
}
