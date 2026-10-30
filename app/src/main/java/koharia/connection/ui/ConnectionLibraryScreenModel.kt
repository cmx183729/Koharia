package koharia.connection.ui

import androidx.paging.PagingData
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey
import koharia.connection.ConnectionSource
import koharia.connection.getAggregatedConnectionLibraryEntries
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
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ConnectionLibraryScreenModel(
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val downloadCount: (Manga) -> Int = { manga -> Injekt.get<DownloadManager>().getDownloadCount(manga) },
    private val ownerLabel: (Long) -> String = { sourceId ->
        val source = sourceManager.get(sourceId)
        (source as? ConnectionSource)?.connectionProfile?.name ?: source?.name.orEmpty()
    },
) : StateScreenModel<ConnectionLibraryScreenModel.State>(State()) {

    private var allEntries: List<ConnectionLibraryEntry> = emptyList()
    private var genreQuery = false
    private var manualDownloadCounts: MutableMap<ConnectionLibraryEntryKey, Int> = mutableMapOf()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    val pages: Flow<PagingData<StateFlow<Manga>>> = state.map { value ->
        PagingData.from(value.entries.map { MutableStateFlow(it.manga) as StateFlow<Manga> })
    }.flowOn(Dispatchers.Default)

    init {
        screenModelScope.launch {
            sourcePreferences.sourceDisplayMode.changes().collect { mode ->
                mutableState.update { it.copy(displayMode = mode) }
            }
        }
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
                val entries = mangaRepository.getAggregatedConnectionLibraryEntries(sourceManager)
                if (generation != refreshGeneration) return@launch
                allEntries = entries
                manualDownloadCounts.clear()
                mutableState.update { it.copy(isLoading = false) }
                applyQuery(mutableState.value.query, genreQuery)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == refreshGeneration) {
                    mutableState.update { it.copy(isLoading = false, error = error) }
                }
            }
        }
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
        val labels = mutableMapOf<Long, String>()
        val resolveOwnerLabel: (Long) -> String = { sourceId ->
            labels.getOrPut(sourceId) { ownerLabel(sourceId) }
        }
        mutableState.update {
            it.copy(
                entries = transformConnectionLibraryEntries(
                    entries = allEntries,
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
}
