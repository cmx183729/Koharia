package koharia.connection.ui

import androidx.paging.PagingData
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import koharia.connection.ConnectionLibraryEntry
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
) : StateScreenModel<ConnectionLibraryScreenModel.State>(State()) {

    private var allEntries: List<ConnectionLibraryEntry> = emptyList()
    private var genreQuery = false
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

    fun refresh() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        refreshJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val entries = mangaRepository.getAggregatedConnectionLibraryEntries(sourceManager)
                if (generation != refreshGeneration) return@launch
                allEntries = entries
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
        mutableState.update { it.copy(entries = filterConnectionLibraryEntries(allEntries, query, genre)) }
    }

    data class State(
        val entries: List<ConnectionLibraryEntry> = emptyList(),
        val query: String = "",
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.default,
        val isLoading: Boolean = true,
        val error: Throwable? = null,
    )
}
