package eu.kanade.tachiyomi.ui.home

import koharia.connection.ConnectionLibraryKind
import koharia.connection.LibraryContentScope

internal enum class HomeLibraryDefaultTab {
    LIBRARY,
    COMICS,
    ALL_COMICS,
}

internal data class HomeLibraryNavigation(
    val classificationEnabled: Boolean,
    val sourceKindSplitEnabled: Boolean,
    val defaultTab: HomeLibraryDefaultTab,
)

/** Keeps home-tab selection derived from active capabilities and registered library kinds. */
internal fun homeLibraryNavigation(
    contentScopes: Set<LibraryContentScope>,
    registeredLibraryKinds: Set<ConnectionLibraryKind> = emptySet(),
): HomeLibraryNavigation {
    val sourceKindSplitEnabled = registeredLibraryKinds.any { it.isFileLibrary() } &&
        ConnectionLibraryKind.SERVER in registeredLibraryKinds
    val classificationEnabled = !sourceKindSplitEnabled &&
        LibraryContentScope.COMIC in contentScopes &&
        LibraryContentScope.BOOK in contentScopes
    return HomeLibraryNavigation(
        classificationEnabled = classificationEnabled,
        sourceKindSplitEnabled = sourceKindSplitEnabled,
        defaultTab = when {
            sourceKindSplitEnabled -> HomeLibraryDefaultTab.ALL_COMICS
            classificationEnabled -> HomeLibraryDefaultTab.COMICS
            else -> HomeLibraryDefaultTab.LIBRARY
        },
    )
}

private fun ConnectionLibraryKind.isFileLibrary(): Boolean = when (this) {
    ConnectionLibraryKind.LOCAL,
    ConnectionLibraryKind.WEBDAV,
    ConnectionLibraryKind.SMB,
    -> true
    else -> false
}
