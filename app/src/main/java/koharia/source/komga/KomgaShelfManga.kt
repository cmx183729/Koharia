package koharia.source.komga

import koharia.komga.api.dto.KOMGA_LIBRARY_NAMESPACE_MEMO_KEY
import koharia.komga.api.dto.mergeKomgaOfflineMemo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.manga.model.Manga

internal fun mergeKomgaShelfManga(
    remote: Manga,
    local: Manga?,
    namespace: String? = null,
): Manga {
    val namespacedRemote = namespace?.let {
        remote.copy(
            memo = JsonObject(remote.memo + (KOMGA_LIBRARY_NAMESPACE_MEMO_KEY to JsonPrimitive(it))),
        )
    } ?: remote
    if (local == null) return namespacedRemote

    val mergedMemo = mergeKomgaOfflineMemo(local.memo, namespacedRemote.memo) ?: local.memo
    val remoteNamespace = namespacedRemote.memo[KOMGA_LIBRARY_NAMESPACE_MEMO_KEY]
    val memo = if (remoteNamespace == null) {
        mergedMemo
    } else {
        JsonObject(mergedMemo + (KOMGA_LIBRARY_NAMESPACE_MEMO_KEY to remoteNamespace))
    }

    return namespacedRemote.copy(
        id = local.id,
        favorite = local.favorite,
        lastUpdate = local.lastUpdate,
        nextUpdate = local.nextUpdate,
        fetchInterval = local.fetchInterval,
        dateAdded = local.dateAdded,
        viewerFlags = local.viewerFlags,
        chapterFlags = local.chapterFlags,
        coverLastModified = local.coverLastModified,
        updateStrategy = local.updateStrategy,
        initialized = local.initialized,
        lastModifiedAt = local.lastModifiedAt,
        favoriteModifiedAt = local.favoriteModifiedAt,
        version = local.version,
        notes = local.notes,
        memo = memo,
    )
}
