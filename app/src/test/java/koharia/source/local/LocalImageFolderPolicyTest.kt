package koharia.source.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StorageEntry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalImageFolderPolicyTest {
    @Test
    fun `one or hundreds of images form a comic while mixed archives remain separate`() {
        assertTrue(isLocalImageComicFolder(1, false))
        assertTrue(isLocalImageComicFolder(500, false))
        assertFalse(isLocalImageComicFolder(0, false))
        assertFalse(isLocalImageComicFolder(500, true))
    }

    @Test
    fun `JHenTai sidecars do not split image pages into individual books`() {
        // Sidecars must remain available to metadata import, but are not other reading units.
        assertFalse(isLocalAuxiliaryFile("ametadata"))
        assertFalse(isLocalAuxiliaryFile("ametadata.json"))
        assertTrue(isLocalImageComicFolder(100, false))
        assertTrue(isLocalAuxiliaryFile("ComicInfo.xml"))
        assertFalse(isLocalAuxiliaryFile("001.jpg"))
    }

    @Test
    fun `aggregated image series is a single reading unit but ordinary series and page records are not`() {
        fun item(kind: LocalLibraryItem.Kind, imageComic: Boolean) = LocalLibraryItem(
            itemKey = "fixture",
            relativePath = "Comic",
            kind = kind,
            format = "directory",
            sizeBytes = 0,
            modifiedAt = 0,
            imageComic = imageComic,
        )
        assertTrue(item(LocalLibraryItem.Kind.SERIES, true).isSingleReadingUnit())
        assertTrue(item(LocalLibraryItem.Kind.FOLDER, true).isSingleReadingUnit())
        assertTrue(item(LocalLibraryItem.Kind.FILE_ENTRY, false).isSingleReadingUnit())
        assertFalse(item(LocalLibraryItem.Kind.SERIES, false).isSingleReadingUnit())
        assertFalse(item(LocalLibraryItem.Kind.FOLDER, false).isSingleReadingUnit())
        assertFalse(item(LocalLibraryItem.Kind.CHAPTER, true).isSingleReadingUnit())
    }

    @Test
    fun `500 remote pages reuse one listing without per-page record decoding`() {
        val context = mockk<Context>()
        val runtime = mockk<NetworkStorageRuntime>()
        val entries = (1..500).map { StorageEntry("Comic/$it.jpg", false, size = 42) }
        val uris = (entries.map { it.path } + "Comic").associateWith {
            mockk<Uri> { every { scheme } returns "fixture" }
        }
        every { runtime.uri(any()) } answers { uris.getValue(firstArg()) }
        every { runtime.cached("Comic") } returns StorageEntry("Comic", true)
        every { runtime.cachedChildren("Comic") } returns entries
        every { runtime.file(any()) } answers {
            com.hippo.unifile.RemoteStorageFile(runtime, firstArg())
        }
        val directory = com.hippo.unifile.RemoteStorageFile(runtime, "Comic")
        val reader = LocalScanDirectoryReader(context)
        org.junit.jupiter.api.Assertions.assertEquals(500, reader.list(directory).size)
        org.junit.jupiter.api.Assertions.assertEquals(500, reader.list(directory).size)
        verify(exactly = 1) { runtime.cachedChildren("Comic") }
        entries.forEach { entry -> verify(exactly = 0) { runtime.cached(entry.path) } }
    }

    @Test
    fun `500 known image MIME entries do not cause per-page provider stats`() {
        val context = mockk<Context>()
        val file = mockk<UniFile>()
        val reader = LocalScanDirectoryReader(context)
        val treeUri = mockk<Uri>()
        repeat(500) {
            assertFalse(reader.isDirectoryEntry(treeUri, "$it", "image/jpeg", file))
        }
        verify(exactly = 0) { file.isDirectory }
        verify(exactly = 0) { context.contentResolver }
    }
}
