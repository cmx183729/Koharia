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
    /** Kind resolved from the owning source; null means the owner was not a registered connection. */
    val libraryKind: ConnectionLibraryKind? = null,
) {
    val sourceId: Long get() = manga.source
    val key: ConnectionLibraryEntryKey get() = ConnectionLibraryEntryKey(sourceId, manga.id)
}

fun Manga.isConnectionLibraryEntry(sourceManager: SourceManager): Boolean {
    if (favorite) return true
    val behavior = (sourceManager.get(source) as? ConnectionMangaBehaviorAdapter)?.mangaBehavior
    return behavior?.providerManagedLibrary == true
}

fun SourceManager.providerManagedLibrarySourceIds(
    allowedLibraryKinds: Set<ConnectionLibraryKind>? = null,
): Set<Long> {
    return getCatalogueSources()
        .filter { source ->
            val behavior = (source as? ConnectionMangaBehaviorAdapter)?.mangaBehavior
            val kind = (source as? ConnectionSource)?.libraryKind
            behavior?.providerManagedLibrary == true &&
                (allowedLibraryKinds == null || (kind != null && kind in allowedLibraryKinds))
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

/** Removes duplicate rows without collapsing equal local ids owned by different connections. */
fun distinctConnectionLibraryManga(mangas: Iterable<Manga>): List<Manga> {
    val seen = HashSet<ConnectionLibraryEntryKey>()
    return mangas.filter { manga ->
        seen.add(ConnectionLibraryEntryKey(manga.source, manga.id))
    }
}

/**
 * Loads provider-managed rows for maintenance operations without allowing one unavailable
 * connection to abort the whole operation. Cancellation remains observable to the caller.
 */
suspend fun MangaRepository.getProviderManagedLibraryEntries(
    sourceManager: SourceManager,
): List<Manga> = sourceManager.providerManagedLibrarySourceIds().flatMap { sourceId ->
    val entries = try {
        getMangaBySourceId(sourceId)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) { "Unable to load provider-managed library entries for source $sourceId" }
        return@flatMap emptyList()
    }
    val ownedEntries = entries.filter { it.source == sourceId }
    val adapter = sourceManager.get(sourceId) as? ConnectionLibraryMembershipAdapter
    if (adapter == null) {
        ownedEntries
    } else {
        try {
            adapter.filterLibraryEntries(ownedEntries).filter { it.source == sourceId }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Unable to filter provider-managed library entries for source $sourceId" }
            emptyList()
        }
    }
}

private suspend fun MangaRepository.getAggregatedProviderManagedLibraryEntries(
    sourceManager: SourceManager,
): List<Manga> = getProviderManagedLibraryEntries(sourceManager)

/**
 * Returns favorite rows plus provider-managed rows without network refresh. Membership adapters may
 * narrow their already cached/indexed local rows; providers without an adapter use their stored rows.
 */
suspend fun MangaRepository.getAggregatedConnectionLibraryEntries(
    sourceManager: SourceManager,
): List<ConnectionLibraryEntry> {
    val favorites = getLibraryManga().map { library ->
        ConnectionLibraryEntry(
            manga = library.manga,
            libraryManga = library,
            libraryKind = (sourceManager.get(library.manga.source) as? ConnectionSource)?.libraryKind,
        )
    }
    val managed = getAggregatedProviderManagedLibraryEntries(sourceManager).map { manga ->
        ConnectionLibraryEntry(
            manga = manga,
            libraryKind = (sourceManager.get(manga.source) as? ConnectionSource)?.libraryKind,
        )
    }
    return mergeConnectionLibraryEntries(favorites + managed)
}
