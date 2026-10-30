package koharia.connection

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Stable owner-aware identity for library entries from different connections. */
data class ConnectionLibraryEntryKey(
    val sourceId: Long,
    val mangaId: Long,
)

/** A library item retains its source owner so aggregate views can route it safely. */
data class ConnectionLibraryEntry(
    val manga: Manga,
    val libraryManga: LibraryManga? = null,
) {
    val sourceId: Long get() = manga.source
    val key: ConnectionLibraryEntryKey get() = ConnectionLibraryEntryKey(sourceId, manga.id)
}

fun Manga.isConnectionLibraryEntry(sourceManager: SourceManager): Boolean {
    if (favorite) return true
    val behavior = (sourceManager.get(source) as? ConnectionMangaBehaviorAdapter)?.mangaBehavior
    return behavior?.providerManagedLibrary == true
}

fun SourceManager.providerManagedLibrarySourceIds(): Set<Long> {
    return getCatalogueSources()
        .filter { source ->
            (source as? ConnectionMangaBehaviorAdapter)?.mangaBehavior?.providerManagedLibrary == true
        }
        .mapTo(mutableSetOf()) { it.id }
}

/**
 * Merges entries by owning source and manga id. A manga id is only local to its source, so a global
 * id-only distinct operation would incorrectly hide entries from different connections.
 */
fun mergeConnectionLibraryEntries(entries: Iterable<ConnectionLibraryEntry>): List<ConnectionLibraryEntry> {
    val result = LinkedHashMap<ConnectionLibraryEntryKey, ConnectionLibraryEntry>()
    entries.forEach { entry -> result.putIfAbsent(entry.key, entry) }
    return result.values.toList()
}

suspend fun MangaRepository.getProviderManagedLibraryEntries(
    sourceManager: SourceManager,
): List<Manga> {
    return sourceManager.providerManagedLibrarySourceIds()
        .flatMap { sourceId ->
            val entries = getMangaBySourceId(sourceId)
            (sourceManager.get(sourceId) as? ConnectionLibraryMembershipAdapter)
                ?.filterLibraryEntries(entries)
                ?: entries
        }
}

private suspend fun MangaRepository.getAggregatedProviderManagedLibraryEntries(
    sourceManager: SourceManager,
): List<Manga> = sourceManager.providerManagedLibrarySourceIds().flatMap { sourceId ->
    val entries = try {
        getMangaBySourceId(sourceId)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) { "Unable to load provider-managed library entries for source $sourceId" }
        return@flatMap emptyList()
    }
    val source = sourceManager.get(sourceId) as? ConnectionLibraryMembershipAdapter ?: return@flatMap entries
    try {
        source.filterLibraryEntries(entries)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) { "Unable to filter provider-managed library entries for source $sourceId" }
        emptyList()
    }
}

/**
 * Returns favorite rows plus provider-managed rows without network refresh. Membership adapters may
 * narrow their already cached/indexed local rows; providers without an adapter use their stored rows.
 */
suspend fun MangaRepository.getAggregatedConnectionLibraryEntries(
    sourceManager: SourceManager,
): List<ConnectionLibraryEntry> {
    val favorites = getLibraryManga().map { library ->
        ConnectionLibraryEntry(library.manga, library)
    }
    val managed = getAggregatedProviderManagedLibraryEntries(sourceManager).map { manga ->
        ConnectionLibraryEntry(manga)
    }
    return mergeConnectionLibraryEntries(favorites + managed)
}
