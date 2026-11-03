package koharia.connection.ui

import androidx.paging.PagingData
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey
import koharia.connection.ConnectionLibraryKind
import koharia.connection.ConnectionLibrarySeedReport
import koharia.connection.ConnectionSource
import koharia.connection.connectionLibrarySeedSourceIds
import koharia.connection.getAggregatedConnectionLibraryEntries
import koharia.connection.seedConnectionLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class ConnectionLibraryScreenModel(
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val allowedLibraryKinds: Set<ConnectionLibraryKind>? = null,
    private val allowedConnectionIds: Set<Long>? = null,
    private val downloadCount: (Manga) -> Int = { manga -> Injekt.get<DownloadManager>().getDownloadCount(manga) },
    private val ownerLabel: (Long) -> String = { sourceId ->
        val source = sourceManager.get(sourceId)
        (source as? ConnectionSource)?.connectionProfile?.name ?: source?.name.orEmpty()
    },
    private val loadChapterProgress: suspend (List<ConnectionLibraryEntry>) -> Map<Long, List<Chapter>> = { entries ->
        val uniqueIds = entries.filter { it.libraryManga == null }.map { it.manga.id }.distinct()
        if (uniqueIds.isEmpty() || uniqueIds.size != entries.count { it.libraryManga == null }) {
            emptyMap()
        } else {
            uniqueIds.chunked(900)
                .flatMap { Injekt.get<ChapterRepository>().getChaptersByMangaIds(it) }
                .groupBy { it.mangaId }
        }
    },
    // The shelf composes the neighbouring pages ahead of a swipe. Loading them eagerly would read
    // the whole library two or three times before the user ever looks at them, so a neighbour is
    // created idle and loaded through ensureLoaded() once it becomes the visible page.
    private val autoRefresh: Boolean = true,
) : StateScreenModel<ConnectionLibraryScreenModel.State>(State()) {

    // refresh() fills these from Dispatchers.IO while the UI thread reads them back through
    // applyQuery, ownerOptions and chapterProgress. Without @Volatile a reader can keep seeing the
    // empty list it saw first, which looks like a library that refuses to load.
    @Volatile private var allEntries: List<ConnectionLibraryEntry> = emptyList()

    @Volatile private var chaptersByMangaId: Map<Long, List<Chapter>> = emptyMap()
    val chapterProgress: Map<Long, List<Chapter>> get() = chaptersByMangaId

    @Volatile private var genreQuery = false

    // Written by the IO refresh and by the download-cache callbacks on the main thread.
    private val manualDownloadCounts = ConcurrentHashMap<ConnectionLibraryEntryKey, Int>()

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var loaded = false

    // One flow per entry, kept across emissions: handing out a new StateFlow every time the state
    // changes would re-subscribe every visible tile and restart its composition on each filter or
    // download-count update.
    private val mangaFlows = ConcurrentHashMap<ConnectionLibraryEntryKey, MutableStateFlow<Manga>>()

    val pages: Flow<PagingData<StateFlow<Manga>>> = state.map { value ->
        val keys = HashSet<ConnectionLibraryEntryKey>(value.entries.size * 2)
        val rows = value.entries.map { entry ->
            keys += entry.key
            mangaFlow(entry)
        }
        if (mangaFlows.size > keys.size + PRUNED_ROW_SLACK) {
            mangaFlows.keys.retainAll(keys)
        }
        PagingData.from(rows)
    }.flowOn(Dispatchers.Default).cachedIn(screenModelScope)

    private fun mangaFlow(entry: ConnectionLibraryEntry): StateFlow<Manga> {
        val flow = mangaFlows.getOrPut(entry.key) { MutableStateFlow(entry.manga) }
        // StateFlow drops a write that is equal to the current value, so an unchanged row costs
        // nothing beyond the comparison.
        if (flow.value != entry.manga) {
            flow.value = entry.manga
        }
        return flow
    }

    init {
        screenModelScope.launch {
            sourcePreferences.sourceDisplayMode.changes().collect { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
        }
        if (autoRefresh) refresh()
    }

    /** Loads the entries once, for pages the shelf created while they were still off screen. */
    fun ensureLoaded() {
        if (loaded) return
        refresh()
    }

    fun setDisplayMode(value: LibraryDisplayMode) {
        sourcePreferences.sourceDisplayMode.set(value)
    }

    fun setFilters(filters: ConnectionLibraryFilters) {
        mutableState.update { it.copy(filters = filters) }
        applyQuery(mutableState.value.query, genreQuery)
    }

    fun ownerLabel(sourceId: Long): String = ownerLabel.invoke(sourceId).ifBlank { sourceId.toString() }

    fun ownerOptions(): List<Pair<Long, String>> = allEntries
        .map { it.sourceId }
        .distinct()
        .map { sourceId -> sourceId to ownerLabel(sourceId) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

    fun libraryKindOptions(): List<ConnectionLibraryKind> = allEntries
        .mapNotNull { it.libraryKind }
        .distinct()
        .sortedBy { it.ordinal }

    fun invalidateDownloadCounts() {
        manualDownloadCounts.clear()
        if (mutableState.value.filters.needsDownloadCounts) applyQuery(mutableState.value.query, genreQuery)
    }

    fun refreshDownloadFilter() {
        if (!mutableState.value.filters.needsDownloadCounts) return
        manualDownloadCounts.clear()
        applyQuery(mutableState.value.query, genreQuery)
    }

    fun invalidateDownloadCount(manga: Manga) {
        manualDownloadCounts.remove(ConnectionLibraryEntryKey(manga.source, manga.id))
        if (mutableState.value.filters.needsDownloadCounts) applyQuery(mutableState.value.query, genreQuery)
    }

    private fun countManualDownloads(entry: ConnectionLibraryEntry): Int =
        manualDownloadCounts.getOrPut(entry.key) { downloadCount(entry.manga) }

    fun refresh() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        refreshJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                // On the first load show what is already cached before touching the network, so a
                // server connection never turns a populated tab into a spinner. Later refreshes
                // keep the rows already on screen for the same reason.
                if (!loaded) {
                    val cached = loadEntries()
                    if (generation != refreshGeneration) return@launch
                    if (cached.isNotEmpty()) publish(generation, cached)
                }

                seedLibrary()
                if (generation != refreshGeneration) return@launch
                publish(generation, loadEntries())
            } catch (error: CancellationException) {
                // A cancelled refresh must not leave the screen pinned on the loading state:
                // re-selecting the tab cancels and restarts this job, and nothing else clears it.
                if (generation == refreshGeneration) {
                    mutableState.update { it.copy(isLoading = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (generation == refreshGeneration) {
                    mutableState.update { it.copy(isLoading = false, error = error) }
                }
            }
        }
    }

    private suspend fun loadEntries(): List<ConnectionLibraryEntry> = filterConnectionLibraryEntriesByKind(
        mangaRepository.getAggregatedConnectionLibraryEntries(sourceManager, allowedConnectionIds),
        allowedLibraryKinds,
    )

    private suspend fun publish(generation: Long, entries: List<ConnectionLibraryEntry>) {
        val chapterProgress = loadChapterProgress(entries)
        if (generation != refreshGeneration) return
        allEntries = entries
        chaptersByMangaId = chapterProgress
        loaded = true
        mutableState.update { it.copy(isLoading = false) }
        manualDownloadCounts.clear()
        applyQuery(mutableState.value.query, genreQuery)
    }

    /**
     * The aggregate library reads local rows only, and a server connection writes those rows when a
     * series is opened. A connection added today therefore had an empty library until something
     * else cached a series, and pulling to refresh could not change that. Fill the catalogue here
     * instead; local and network-share connections keep using their own scan.
     */
    private suspend fun seedLibrary(): ConnectionLibrarySeedReport = try {
        seedConnectionLibrary(
            sourceManager = sourceManager,
            mangaRepository = mangaRepository,
            sourceIds = sourceManager.connectionLibrarySeedSourceIds(
                allowedSourceIds = allowedConnectionIds,
                allowedLibraryKinds = allowedLibraryKinds,
            ),
        )
    } catch (error: Throwable) {
        // Filling the catalogue is best effort: a server that is down, a provider that cannot
        // list, or a repository that refuses must never fail the library refresh itself.
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) { "Unable to seed the connection library" }
        ConnectionLibrarySeedReport()
    }

    fun search(query: String) {
        genreQuery = false
        mutableState.update { it.copy(query = query) }
        applyQuery(query, false)
    }

    fun searchGenre(name: String) {
        genreQuery = true
        mutableState.update { it.copy(query = name) }
        applyQuery(name, true)
    }

    private fun applyQuery(query: String, genre: Boolean) {
        val filters = mutableState.value.filters
        // Read the backing list once: a refresh running on IO can replace it between two reads,
        // which would filter one snapshot and sort another.
        val entries = allEntries
        val labels = mutableMapOf<Long, String>()
        val resolveOwnerLabel: (Long) -> String = { sourceId ->
            labels.getOrPut(sourceId) { ownerLabel(sourceId) }
        }
        mutableState.update {
            it.copy(
                entries = transformConnectionLibraryEntries(
                    entries = entries,
                    query = query,
                    genreQuery = genre,
                    filters = filters,
                    manualDownloadCount = if (filters.needsDownloadCounts) {
                        ::countManualDownloads
                    } else {
                        { _: ConnectionLibraryEntry -> 0 }
                    },
                    ownerLabel = if (filters.needsOwnerLabels) {
                        resolveOwnerLabel
                    } else {
                        { sourceId: Long ->
                            sourceId.toString()
                        }
                    },
                ),
            )
        }
    }

    data class State(
        val entries: List<ConnectionLibraryEntry> = emptyList(),
        val query: String = "",
        val filters: ConnectionLibraryFilters = ConnectionLibraryFilters(),
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.default,
        val isLoading: Boolean = true,
        val error: Throwable? = null,
    )

    private companion object {
        /**
         * Rows kept in the flow cache beyond the current entry count. Refiltering a large library
         * re-visits most keys, so pruning on every emission would defeat the cache; only a real
         * shrink (a filter narrowing 20k rows to 200) clears it.
         */
        const val PRUNED_ROW_SLACK = 512
    }
}
