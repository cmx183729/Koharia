package koharia.connection.ui

import eu.kanade.presentation.library.components.MangaReadProgress
import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey

internal fun connectionLibraryReadProgress(
    entries: List<ConnectionLibraryEntry>,
): Map<ConnectionLibraryEntryKey, MangaReadProgress?> = entries.associate { entry ->
    entry.key to entry.libraryManga?.takeIf { it.totalChapters > 0 }?.let {
        MangaReadProgress(it.readCount.coerceIn(0, it.totalChapters), it.totalChapters)
    }
}
