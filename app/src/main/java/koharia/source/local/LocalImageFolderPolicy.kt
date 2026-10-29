package koharia.source.local

/** Sidecars and unrelated files do not turn the pages of one comic into separate books. */
internal fun isLocalImageComicFolder(imageCount: Int, hasOtherReadingFiles: Boolean): Boolean =
    imageCount > 0 && !hasOtherReadingFiles

internal fun LocalLibraryItem.isSingleReadingUnit(): Boolean =
    kind == LocalLibraryItem.Kind.FILE_ENTRY ||
        (imageComic && kind in setOf(LocalLibraryItem.Kind.FOLDER, LocalLibraryItem.Kind.SERIES))
