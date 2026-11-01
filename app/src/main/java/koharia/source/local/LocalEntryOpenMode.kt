package koharia.source.local

import koharia.connection.EntryOpenMode

internal fun localEntryOpenMode(
    isImageComic: Boolean,
    isIndividualBook: Boolean,
    isIndividualFile: Boolean,
    bookMode: EntryOpenMode,
    comicMode: EntryOpenMode,
): EntryOpenMode = when {
    isImageComic -> EntryOpenMode.READER
    isIndividualBook -> bookMode
    isIndividualFile -> comicMode
    else -> EntryOpenMode.DETAILS
}
