package koharia.connection.ui

import eu.kanade.tachiyomi.source.model.SManga
import koharia.connection.ConnectionLibraryEntry

enum class ConnectionLibrarySort {
    TITLE,
    AUTHOR,
    DATE_ADDED,
    LAST_UPDATE,
    OWNER,
}

data class ConnectionLibraryFilters(
    val ownerSourceId: Long? = null,
    val favoritesOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    val sort: ConnectionLibrarySort = ConnectionLibrarySort.TITLE,
    val ascending: Boolean = true,
) {
    val isActive: Boolean
        get() = ownerSourceId != null || favoritesOnly || completedOnly || downloadedOnly ||
            sort != ConnectionLibrarySort.TITLE || !ascending

    val needsDownloadCounts: Boolean
        get() = downloadedOnly

    val needsOwnerLabels: Boolean
        get() = sort == ConnectionLibrarySort.OWNER
}

internal fun filterConnectionLibraryEntries(
    entries: List<ConnectionLibraryEntry>,
    query: String,
    genre: Boolean,
): List<ConnectionLibraryEntry> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return entries
    return if (genre) {
        entries.filter { entry ->
            entry.manga.genre.orEmpty().any { it.contains(normalized, ignoreCase = true) }
        }
    } else {
        entries.filter { entry ->
            sequenceOf(entry.manga.title, entry.manga.author, entry.manga.artist)
                .filterNotNull()
                .any { it.contains(normalized, ignoreCase = true) }
        }
    }
}

internal fun transformConnectionLibraryEntries(
    entries: List<ConnectionLibraryEntry>,
    query: String,
    genreQuery: Boolean,
    filters: ConnectionLibraryFilters,
    manualDownloadCount: (ConnectionLibraryEntry) -> Int = { 0 },
    ownerLabel: (Long) -> String = Long::toString,
): List<ConnectionLibraryEntry> {
    val filtered = filterConnectionLibraryEntries(entries, query, genreQuery)
        .asSequence()
        .filter { filters.ownerSourceId == null || it.sourceId == filters.ownerSourceId }
        .filter { !filters.favoritesOnly || it.manga.favorite }
        .filter { !filters.completedOnly || it.manga.status == SManga.COMPLETED.toLong() }
        .filter { !filters.downloadedOnly || manualDownloadCount(it) > 0 }
        .toList()
    val comparator = when (filters.sort) {
        ConnectionLibrarySort.TITLE -> compareBy<ConnectionLibraryEntry, String>(String.CASE_INSENSITIVE_ORDER) {
            it.manga.title
        }
        ConnectionLibrarySort.AUTHOR -> compareBy<ConnectionLibraryEntry, String>(String.CASE_INSENSITIVE_ORDER) {
            it.manga.author.orEmpty()
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title }
        ConnectionLibrarySort.DATE_ADDED -> compareBy<ConnectionLibraryEntry> { it.manga.dateAdded }
        ConnectionLibrarySort.LAST_UPDATE -> compareBy<ConnectionLibraryEntry> { it.manga.lastUpdate }
        ConnectionLibrarySort.OWNER -> compareBy<ConnectionLibraryEntry, String>(String.CASE_INSENSITIVE_ORDER) {
            ownerLabel(it.sourceId)
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title }
    }
    val direction = if (filters.ascending) comparator else comparator.reversed()
    return filtered.sortedWith(
        direction.thenBy { it.sourceId }.thenBy { it.manga.id },
    )
}
