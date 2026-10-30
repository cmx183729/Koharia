package koharia.connection.ui

import eu.kanade.tachiyomi.data.download.DownloadManager
import koharia.connection.MangaDownloadState
import tachiyomi.domain.manga.model.Manga

internal fun aggregateDownloadState(manga: Manga, downloads: DownloadManager): MangaDownloadState =
    MangaDownloadState(downloads.getDownloadCount(manga).toLong(), total = null)
