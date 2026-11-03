package koharia.connection.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.StringResource
import koharia.connection.ConnectionLibraryKind
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionSource
import koharia.connection.LibraryConnectionProfile
import koharia.connection.NO_ACTIVE_CONNECTION
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.LocalEInkDisplayPolicy
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One entry of the library shelf bar.
 *
 * [libraryIds] is the exact set of connection ids whose entries the page is allowed to show. The
 * aggregate entry carries every visible non-server library, a server entry carries a single server,
 * and a plain library entry carries itself.
 */
internal data class LibraryShelfTab(
    val key: String,
    val title: String,
    val libraryIds: Set<Long>,
)

/**
 * Builds the shelf bar in display order: the aggregate entry first, then the servers, then the
 * remaining libraries.
 *
 * - Servers never reach the aggregate entry: they are a separate kind of library and mixing them
 *   would make the aggregate view depend on a remote server being reachable.
 * - With zero or one visible non-server library the aggregate entry is the only one of its group:
 *   a single library tab named after itself next to an aggregate tab that holds the same rows is
 *   just noise.
 * - Hidden libraries are dropped before anything is computed, so they also leave the aggregate.
 */
internal fun buildLibraryShelfTabs(
    profiles: List<LibraryConnectionProfile>,
    hiddenIds: Set<Long>,
    kindOf: (Long) -> ConnectionLibraryKind?,
    aggregateTitle: String,
): List<LibraryShelfTab> {
    val visible = profiles.filter { it.id !in hiddenIds }
    val servers = visible.filter { kindOf(it.id) == ConnectionLibraryKind.SERVER }
    val others = visible.filter { kindOf(it.id) != ConnectionLibraryKind.SERVER }

    val tabs = mutableListOf<LibraryShelfTab>()
    if (others.isNotEmpty()) {
        tabs += LibraryShelfTab(
            key = "all",
            title = aggregateTitle,
            libraryIds = others.mapTo(linkedSetOf(), LibraryConnectionProfile::id),
        )
    }
    servers.forEach { profile ->
        tabs += LibraryShelfTab(
            key = "library:${profile.id}",
            title = profile.name,
            libraryIds = setOf(profile.id),
        )
    }
    if (others.size > 1) {
        others.forEach { profile ->
            tabs += LibraryShelfTab(
                key = "library:${profile.id}",
                title = profile.name,
                libraryIds = setOf(profile.id),
            )
        }
    }
    return tabs
}

/**
 * Library host with a swipeable shelf bar. Every page owns its own screen model restricted to the
 * library it represents, so switching libraries never re-reads the whole library table and never
 * mixes rows from a server into a local page.
 */
@Composable
internal fun Screen.ConnectionLibraryShelf(
    titleRes: StringResource,
    onActiveModelChanged: (ConnectionLibraryScreenModel) -> Unit,
) {
    val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val profiles by remember(connectionPreferences) { connectionPreferences.profilesChanges() }
        .collectAsState(connectionPreferences.getProfiles())
    val hiddenIds by remember(connectionPreferences) { connectionPreferences.hiddenLibraryIdsChanges() }
        .collectAsState(connectionPreferences.hiddenLibraryIds())
    val catalogueSources by remember(sourceManager) { sourceManager.catalogueSources }
        .collectAsState(sourceManager.getCatalogueSources())
    val kinds = remember(catalogueSources) {
        catalogueSources
            .mapNotNull { source -> (source as? ConnectionSource)?.let { source.id to it.libraryKind } }
            .toMap()
    }
    val aggregateTitle = stringResource(MR.strings.all)
    val tabs = remember(profiles, hiddenIds, kinds, aggregateTitle) {
        buildLibraryShelfTabs(
            profiles = profiles,
            hiddenIds = hiddenIds,
            kindOf = { id -> kinds[id] },
            aggregateTitle = aggregateTitle,
        )
    }

    if (tabs.isEmpty()) {
        EmptyScreen(MR.strings.connection_library_shelf_empty)
        return
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    // Hiding a library must not slide the user onto whichever page took over its index: remember
    // the selected entry by key and move the pager to wherever that key lives now.
    var selectedKey by remember { mutableStateOf(tabs.first().key) }
    LaunchedEffect(tabs) {
        val target = tabs.indexOfFirst { it.key == selectedKey }
        if (target >= 0) {
            if (target != pagerState.currentPage) {
                pagerState.scrollToPage(target)
            }
        } else {
            val clamped = pagerState.currentPage.coerceIn(0, tabs.lastIndex)
            if (clamped != pagerState.currentPage) {
                pagerState.scrollToPage(clamped)
            }
            selectedKey = tabs[clamped].key
        }
    }
    val currentIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex)
    LaunchedEffect(currentIndex) {
        tabs.getOrNull(currentIndex)?.let { selectedKey = it.key }
    }
    val currentTab = tabs[currentIndex]
    val currentModel = rememberScreenModel(tag = "shelf:${currentTab.key}") {
        ConnectionLibraryScreenModel(allowedConnectionIds = currentTab.libraryIds)
    }
    SideEffect { onActiveModelChanged(currentModel) }

    val state by currentModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var showFilters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val eInkEnabled = LocalEInkDisplayPolicy.current.enabled
    // The search field belongs to the shelf, the filter itself belongs to the page. Leaving a page
    // with a filter it no longer shows would hide rows behind an empty search box.
    LaunchedEffect(currentIndex) {
        searchQuery = null
    }

    // A single page has nothing to switch to, and the title already names it.
    val titleSlot: (@Composable () -> Unit)? = if (tabs.size > 1) {
        {
            LibraryShelfTabStrip(
                tabs = tabs,
                selectedIndex = currentIndex,
                onSelect = { index ->
                    scope.launch {
                        if (eInkEnabled) {
                            pagerState.scrollToPage(index)
                        } else {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                    searchQuery = null
                },
            )
        }
    } else {
        null
    }

    ConnectionLibraryFilterSheet(model = currentModel, visible = showFilters, onDismiss = { showFilters = false })

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { scrollBehavior ->
            ConnectionLibraryToolbar(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSearch = currentModel::search,
                onCloseSearch = {
                    searchQuery = null
                    currentModel.search("")
                },
                displayMode = state.displayMode,
                onDisplayModeChange = currentModel::setDisplayMode,
                connectionProfiles = emptyList(),
                activeConnectionId = NO_ACTIVE_CONNECTION,
                onConnectionSelect = {},
                onFilterClick = { showFilters = true },
                onRefresh = currentModel::refresh,
                navigateUp = null,
                showFilterAction = true,
                hasActiveFilters = state.filters.isActive,
                showConnectionAction = false,
                title = currentTab.title.ifEmpty { stringResource(titleRes) },
                titleSlot = titleSlot,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        HorizontalPager(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            state = pagerState,
            key = { page -> tabs.getOrNull(page)?.key ?: "page:$page" },
        ) { page ->
            // Keep only the neighbours of the current page alive. Reading a library builds the whole
            // entry list in memory, so mounting every page at once would defeat the point of
            // splitting the library into pages.
            if (page !in (currentIndex - 1)..(currentIndex + 1)) return@HorizontalPager
            val tab = tabs.getOrNull(page) ?: return@HorizontalPager
            val isCurrent = page == currentIndex
            // A neighbour stays idle until it is swiped onto: reading its entries here would pay
            // for the next page before the user asked for it.
            val pageModel = rememberScreenModel(tag = "shelf:${tab.key}") {
                ConnectionLibraryScreenModel(
                    allowedConnectionIds = tab.libraryIds,
                    autoRefresh = isCurrent,
                )
            }
            if (isCurrent) {
                LaunchedEffect(page) { pageModel.ensureLoaded() }
            } else {
                LaunchedEffect(page) { pageModel.search("") }
            }
            ConnectionLibraryBody(
                model = pageModel,
                snackbar = snackbar,
                reportErrors = isCurrent,
            )
        }
    }
}

/** Horizontal, swipeable library bar. It scrolls itself to keep the selected entry visible. */
@Composable
internal fun LibraryShelfTabStrip(
    tabs: List<LibraryShelfTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val eInkEnabled = LocalEInkDisplayPolicy.current.enabled
    LaunchedEffect(selectedIndex, tabs.size) {
        if (tabs.isNotEmpty()) {
            val target = selectedIndex.coerceIn(0, tabs.lastIndex)
            if (eInkEnabled) {
                listState.scrollToItem(target)
            } else {
                listState.animateScrollToItem(target)
            }
        }
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(tabs, key = { _, tab -> tab.key }) { index, tab ->
            LibraryShelfTabItem(
                title = tab.title,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun LibraryShelfTabItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
    )
}
