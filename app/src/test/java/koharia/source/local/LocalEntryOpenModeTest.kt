package koharia.source.local

import io.kotest.matchers.shouldBe
import koharia.connection.EntryOpenMode
import org.junit.jupiter.api.Test

class LocalEntryOpenModeTest {
    @Test
    fun `image comics always open in reader`() {
        localEntryOpenMode(
            isImageComic = true,
            isIndividualBook = false,
            isIndividualFile = false,
            bookMode = EntryOpenMode.DETAILS,
            comicMode = EntryOpenMode.DETAILS,
        ) shouldBe EntryOpenMode.READER
    }

    @Test
    fun `individual entries use their configured mode`() {
        localEntryOpenMode(
            isImageComic = false,
            isIndividualBook = true,
            isIndividualFile = true,
            bookMode = EntryOpenMode.READER,
            comicMode = EntryOpenMode.PAGE_PREVIEW,
        ) shouldBe EntryOpenMode.READER
        localEntryOpenMode(
            isImageComic = false,
            isIndividualBook = false,
            isIndividualFile = true,
            bookMode = EntryOpenMode.DETAILS,
            comicMode = EntryOpenMode.PAGE_PREVIEW,
        ) shouldBe EntryOpenMode.PAGE_PREVIEW
    }

    @Test
    fun `non-file entries open details`() {
        localEntryOpenMode(
            isImageComic = false,
            isIndividualBook = false,
            isIndividualFile = false,
            bookMode = EntryOpenMode.READER,
            comicMode = EntryOpenMode.READER,
        ) shouldBe EntryOpenMode.DETAILS
    }
}
