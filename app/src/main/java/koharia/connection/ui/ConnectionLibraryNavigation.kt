package koharia.connection.ui

import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.domain.manga.model.Manga

/** Builds the canonical details route while retaining the manga's owning source identity. */
fun Manga.toConnectionDetailsScreen(): MangaScreen = MangaScreen(
    mangaId = id,
    fromSource = true,
    sourceId = source,
    mangaUrl = url,
)
