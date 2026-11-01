package koharia.connection.ui

import eu.kanade.presentation.library.components.MangaReadProgress
import koharia.connection.ConnectionLibraryEntry
import koharia.connection.ConnectionLibraryEntryKey
import tachiyomi.domain.chapter.model.Chapter

internal fun connectionLibraryReadProgress(
    entries: List<ConnectionLibraryEntry>,
    chaptersByMangaId: Map<Long, List<Chapter>> = emptyMap(),
): Map<ConnectionLibraryEntryKey, MangaReadProgress?> {
    val entriesByMangaId = entries.groupingBy { it.manga.id }.eachCount()
    return entries.associate { entry ->
        val libraryProgress = entry.libraryManga?.takeIf { it.totalChapters > 0 }?.let {
            MangaReadProgress(it.readCount.coerceIn(0, it.totalChapters), it.totalChapters)
        }
        val chapterProgress = if (entriesByMangaId[entry.manga.id] == 1) {
            chaptersByMangaId[entry.manga.id].orEmpty().takeIf { it.isNotEmpty() }?.let { chapters ->
                MangaReadProgress(chapters.count { it.read }.toLong(), chapters.size.toLong())
            }
        } else {
            null
        }
        entry.key to (libraryProgress ?: chapterProgress)
    }
}
