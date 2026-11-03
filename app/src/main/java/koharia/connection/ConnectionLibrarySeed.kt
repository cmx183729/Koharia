package koharia.connection

import eu.kanade.tachiyomi.source.CatalogueSource
import koharia.domain.manga.model.toDomainManga
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

internal data class ConnectionLibrarySeedReport(
    val sources: Int = 0,
    val entries: Int = 0,
    val failures: Int = 0,
)

/**
 * Server connections whose aggregate library tab can only be filled by fetching their catalogue.
 * Connections backed by a filesystem (local, WebDAV, SMB) index their own rows instead and are
 * deliberately excluded: seeding them would boot a library scan from the library screen.
 */
internal fun SourceManager.connectionLibrarySeedSourceIds(
    allowedSourceIds: Set<Long>? = null,
    allowedLibraryKinds: Set<ConnectionLibraryKind>? = null,
): List<Long> = getCatalogueSources()
    .mapNotNull { source -> (source as? ConnectionSource)?.let { source to it } }
    .filter { (_, connection) ->
        connection.libraryKind == ConnectionLibraryKind.SERVER &&
            (allowedSourceIds == null || connection.id in allowedSourceIds) &&
            (allowedLibraryKinds == null || connection.libraryKind in allowedLibraryKinds)
    }
    .filter { (_, connection) ->
        (connection as? ConnectionMangaBehaviorAdapter)?.mangaBehavior?.providerManagedLibrary == true
    }
    .map { (source, _) -> source.id }

/**
 * Fills the persistent manga table for server connections that have nothing cached yet.
 *
 * The aggregate library reads only local rows, and a remote provider writes those rows when a
 * series details screen is opened — so a freshly added server showed an empty library until the
 * user happened to open one series from it. Fetching the catalogue once puts the rows in place.
 *
 * Sources that already have rows are refreshed incrementally: pages are read from the front (the
 * order servers use for newly added series) and the walk stops at the first page that added nothing,
 * so a warm library costs a single request.
 */
internal suspend fun seedConnectionLibrary(
    sourceManager: SourceManager,
    mangaRepository: MangaRepository,
    sourceIds: Collection<Long>,
    maxPagesPerSource: Int = MAX_PAGES_PER_SOURCE,
    maxKnownPagesPerSource: Int = MAX_KNOWN_PAGES_PER_SOURCE,
    onEntriesInserted: suspend () -> Unit = {},
): ConnectionLibrarySeedReport {
    if (sourceIds.isEmpty()) return ConnectionLibrarySeedReport()

    var seededSources = 0
    var seededEntries = 0
    var failures = 0

    for (sourceId in sourceIds) {
        currentCoroutineContext().ensureActive()
        val source = sourceManager.get(sourceId) as? CatalogueSource ?: continue
        val known = try {
            mangaRepository.getMangaBySourceId(sourceId).mapTo(HashSet()) { it.url }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            source.logcat(LogPriority.WARN, error) { "Unable to read cached rows for source $sourceId" }
            failures++
            continue
        }
        val hadEntries = known.isNotEmpty()
        // Reselecting the library tab refreshes, and every refresh would otherwise ask each server
        // for its first page. A warm connection is only revisited once the interval has passed; an
        // empty one is always retried so a connection that was unreachable can recover.
        if (hadEntries && isRecentlySeeded(sourceId)) continue
        markSeeded(sourceId)
        var page = 1
        var knownPages = 0
        var inserted = 0
        var settled = false

        while (page <= maxPagesPerSource) {
            currentCoroutineContext().ensureActive()
            val mangasPage = try {
                source.getPopularManga(page)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                source.logcat(LogPriority.WARN, error) { "Unable to fetch page $page for source $sourceId" }
                failures++
                break
            }
            val fresh = mangasPage.mangas
                .map { smanga -> seedRow(source, smanga.toDomainManga(sourceId)) }
                .filter { it.url !in known }
            if (fresh.isEmpty()) {
                knownPages++
            } else {
                knownPages = 0
                try {
                    mangaRepository.insertNetworkManga(fresh)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    source.logcat(LogPriority.WARN, error) { "Unable to cache rows for source $sourceId" }
                    failures++
                    break
                }
                known += fresh.map { it.url }
                inserted += fresh.size
                seededEntries += fresh.size
            }
            if (!mangasPage.hasNextPage) {
                settled = true
                break
            }
            // A page that added nothing means the catalogue is caught up. Walking further would
            // only re-read rows that are already in the table, so stop either way.
            if (knownPages >= maxKnownPagesPerSource) {
                settled = true
                break
            }
            page++
        }

        if (inserted > 0) {
            seededSources++
            onEntriesInserted()
        }
        if (!settled && inserted >= maxPagesPerSource * PAGE_SIZE_HINT) {
            source.logcat(LogPriority.INFO) {
                "Library seeding for source $sourceId stopped at $maxPagesPerSource pages"
            }
        }
    }

    return ConnectionLibrarySeedReport(sources = seededSources, entries = seededEntries, failures = failures)
}

/**
 * Runs the source's own shelf merge so the cached row carries the membership evidence its
 * [ConnectionLibraryMembershipAdapter] later requires (Komga stores a cache namespace in the memo).
 * A source that cannot merge yet still gets its row: the next browse pass repairs it.
 */
private fun seedRow(source: CatalogueSource, manga: Manga): Manga {
    val adapter = source as? ConnectionShelfLocalStateAdapter ?: return manga
    return runCatching { adapter.mergeShelfLocalState(manga, null) }.getOrDefault(manga)
}

/** Pages read for a connection with an empty cache before the fill is cut off. */
private const val MAX_PAGES_PER_SOURCE = 60

/** Pages a warm connection may read before the catalogue counts as caught up. */
private const val MAX_KNOWN_PAGES_PER_SOURCE = 1

/** Order-of-magnitude page size used only to decide whether a fill was truncated. */
private const val PAGE_SIZE_HINT = 20

/** How long a connection with cached rows is left alone after being read once. */
private const val RESEED_INTERVAL_MILLIS = 30_000L

private val reseededAt = ConcurrentHashMap<Long, Long>()

private fun isRecentlySeeded(sourceId: Long): Boolean {
    val last = reseededAt[sourceId] ?: return false
    return System.currentTimeMillis() - last < RESEED_INTERVAL_MILLIS
}

private fun markSeeded(sourceId: Long) {
    reseededAt[sourceId] = System.currentTimeMillis()
}
