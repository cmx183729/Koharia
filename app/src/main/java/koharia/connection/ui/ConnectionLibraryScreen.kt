package koharia.connection.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionLibraryEntryKey
import koharia.connection.MangaDownloadState
import koharia.connection.NO_ACTIVE_CONNECTION
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Aggregate library host. It only reads persisted local membership and never refreshes a provider. */
class ConnectionLibraryScreen : Screen(), ConnectionBrowseScreen {
    override val sourceId: Long = NO_ACTIVE_CONNECTION
    override val refreshOnReselect: Boolean = true

    @Transient private var runtimeModel: ConnectionLibraryScreenModel? = null

    override suspend fun search(query: String) {
        runtimeModel?.search(query)
    }

    override suspend fun searchGenre(name: String) {
        runtimeModel?.searchGenre(name)
    }

    override suspend fun refresh() {
        runtimeModel?.refresh()
    }

    @Composable
    override fun Content() {
        val model = rememberScreenModel { ConnectionLibraryScreenModel() }
        runtimeModel = model
        var searchQuery by remember { mutableStateOf<String?>(null) }
        val state by model.state.collectAsState()
        val pages = model.pages.collectAsLazyPagingItems()
        val navigator = LocalNavigator.currentOrThrow
        val snackbar = remember { SnackbarHostState() }
        val refreshFailedMessage = stringResource(MR.strings.connection_library_refresh_failed)
        LaunchedEffect(state.error, state.entries.isNotEmpty()) {
            if (state.error != null && state.entries.isNotEmpty()) {
                snackbar.showSnackbar(refreshFailedMessage)
            }
        }
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val configuration = LocalConfiguration.current
        val columnPreference = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }
        val columns by columnPreference.collectAsState()
        val progressByKey = remember(state.entries) { connectionLibraryReadProgress(state.entries) }
        var showFilters by remember { mutableStateOf(false) }
        val ownerOptions = remember(state.entries) { model.ownerOptions() }
        val sortOptions = listOf(
            ConnectionLibrarySort.TITLE to stringResource(MR.strings.connection_library_sort_title),
            ConnectionLibrarySort.AUTHOR to stringResource(MR.strings.connection_library_sort_author),
            ConnectionLibrarySort.DATE_ADDED to stringResource(MR.strings.action_sort_date_added),
            ConnectionLibrarySort.LAST_UPDATE to stringResource(MR.strings.action_sort_last_manga_update),
            ConnectionLibrarySort.OWNER to stringResource(MR.strings.connection_library_sort_owner),
        )
        val ownerLabels = remember(ownerOptions, model) { ownerOptions.toMap() }
        val downloads = remember { Injekt.get<DownloadManager>() }
        val downloadCounts = remember { mutableStateOf<Map<ConnectionLibraryEntryKey, Long>>(emptyMap()) }
        val onDownloadState = remember(downloads, model) {
            { manga: tachiyomi.domain.manga.model.Manga ->
                val key = ConnectionLibraryEntryKey(manga.source, manga.id)
                downloadCounts.value[key]?.let { MangaDownloadState(it, null) }
                    ?: aggregateDownloadState(manga, downloads).also { value ->
                        downloadCounts.value = downloadCounts.value + (key to value.count)
                    }
            }
        }
        LaunchedEffect(downloads, onDownloadState, state.entries) {
            downloads.cacheUpdates.collect { change ->
                if (change.sourceId == null && change.mangaDirectories == null) {
                    downloadCounts.value = emptyMap()
                    model.invalidateDownloadCounts()
                } else {
                    val affected = state.entries.filter { entry ->
                        downloads.isAffectedByCacheUpdate(entry.manga, change)
                    }
                    affected.forEach { entry ->
                        downloadCounts.value = downloadCounts.value - entry.key
                        model.invalidateDownloadCount(entry.manga)
                    }
                }
            }
        }

        if (showFilters) {
            ConnectionLibraryAllFilterSheet(
                owners = ownerOptions,
                sortOptions = sortOptions,
                filters = state.filters,
                onDismiss = { showFilters = false },
                onApply = { filters ->
                    model.setFilters(filters)
                    model.refreshDownloadFilter()
                    showFilters = false
                },
            )
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { scrollBehavior ->
                ConnectionLibraryToolbar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onSearch = model::search,
                    onCloseSearch = {
                        searchQuery = null
                        model.search("")
                    },
                    displayMode = state.displayMode,
                    onDisplayModeChange = model::setDisplayMode,
                    connectionProfiles = emptyList(),
                    activeConnectionId = NO_ACTIVE_CONNECTION,
                    onConnectionSelect = {},
                    onFilterClick = { showFilters = true },
                    onRefresh = model::refresh,
                    navigateUp = null,
                    showFilterAction = true,
                    hasActiveFilters = state.filters.isActive,
                    showConnectionAction = false,
                    title = stringResource(MR.strings.label_library),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when {
                state.isLoading && state.entries.isEmpty() -> LoadingScreen(Modifier.fillMaxSize())
                state.error != null && state.entries.isEmpty() ->
                    EmptyScreen(
                        message = state.error?.message ?: stringResource(MR.strings.connection_unavailable),
                        actions = kotlinx.collections.immutable.persistentListOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_retry,
                                icon = Icons.Outlined.Refresh,
                                onClick = model::refresh,
                            ),
                        ),
                    )
                state.entries.isEmpty() -> EmptyScreen(
                    message = stringResource(MR.strings.no_results_found),
                    actions = kotlinx.collections.immutable.persistentListOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = model::refresh,
                        ),
                    ),
                )
                else -> BrowseSourceContent(
                    modifier = Modifier.fillMaxSize(),
                    source = null,
                    mangaList = pages,
                    columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(120.dp),
                    displayMode = state.displayMode,
                    snackbarHostState = snackbar,
                    contentPadding = PaddingValues(0.dp),
                    showLibraryBadges = false,
                    readProgress = { manga -> progressByKey[ConnectionLibraryEntryKey(manga.source, manga.id)] },
                    manualDownloadState = onDownloadState,
                    entryLabel = { manga -> ownerLabels[manga.source] ?: model.ownerLabel(manga.source) },
                    onWebViewClick = {},
                    onHelpClick = {},
                    onMangaClick = { navigator.push(it.toConnectionDetailsScreen()) },
                    onMangaLongClick = { navigator.push(it.toConnectionDetailsScreen()) },
                    onRefresh = model::refresh,
                )
            }
        }
    }
}
