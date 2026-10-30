package koharia.connection.ui

import koharia.connection.ConnectionLibraryEntry

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
        entries.filter { it.manga.title.contains(normalized, ignoreCase = true) }
    }
}
