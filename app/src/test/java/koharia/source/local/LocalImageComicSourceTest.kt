package koharia.source.local

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.util.DisplayMetrics
import com.hippo.unifile.UniFile
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class LocalImageComicSourceTest {
    @ParameterizedTest
    @EnumSource(LocalLibraryOrganizationMode::class)
    fun `image directory exposes one chapter and one shelf unit despite indexed pages`(
        mode: LocalLibraryOrganizationMode,
    ) = runTest {
        val previousInjekt = Injekt
        patchInjekt()
        mockkConstructor(LocalLibraryPreferences::class)
        val sourceId = 42L
        val root =
            LocalLibraryRootConfig(id = "root", contentType = LocalLibraryContentType.COMICS, bookshelfId = "shelf")
        val locator = ".koharia/nodes/image-comic"
        val item = LocalLibraryItem(
            itemKey = LocalLibraryLocator.itemKey(root.id, locator),
            rootId = root.id,
            relativePath = "Comic",
            kind = when (mode) {
                LocalLibraryOrganizationMode.SERIES -> LocalLibraryItem.Kind.SERIES
                LocalLibraryOrganizationMode.FOLDER -> LocalLibraryItem.Kind.FOLDER
                LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> LocalLibraryItem.Kind.FILE_ENTRY
            },
            format = "directory",
            sizeBytes = 0,
            modifiedAt = 123,
            locatorPath = locator,
            imageComic = true,
            virtualType = LocalLibraryItem.VirtualType.IMAGE_SERIES.takeIf {
                mode == LocalLibraryOrganizationMode.INDIVIDUAL_FILES
            },
        )
        val pages = listOf("1.jpg", "2.jpg", "10.jpg").map { name ->
            LocalLibraryItem(
                itemKey = "$name-key",
                rootId = root.id,
                relativePath = "Comic/$name",
                kind = LocalLibraryItem.Kind.CHAPTER,
                format = "jpg",
                sizeBytes = 100,
                modifiedAt = 123,
            )
        }
        val index = LocalLibraryIndex(items = listOf(item) + pages, pendingChapterRefreshItemKeys = setOf(item.itemKey))
        val config = LocalLibraryConfig(
            roots = listOf(root),
            bookshelves = listOf(LocalBookshelf("shelf", "Fixture", LocalLibraryContentType.COMICS, mode)),
            setupCompleted = true,
        )
        val sharedPreferences = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { secondArg() }
        }
        val application = mockk<Application> {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } returns sharedPreferences
        }
        fun image(filename: String): UniFile = mockk {
            every { uri } returns mockk<Uri> { every { scheme } returns "fixture" }
            every { name } returns filename
            every { isDirectory } returns false
            every { length() } returns 100
            every { lastModified() } returns 123
            every { openInputStream() } answers { filename.byteInputStream() }
        }
        val images = listOf("10.jpg", "2.jpg", "1.jpg").map(::image)
        val directory = mockk<UniFile> {
            every { uri } returns mockk<Uri> { every { scheme } returns "fixture" }
            every { isDirectory } returns true
            every { name } returns "Comic"
            every { length() } returns 0
            every { lastModified() } returns 123
            every { listFiles() } returns images.toTypedArray()
        }
        val base = mockk<UniFile> { every { findFile("Comic") } returns directory }
        val repository = mockk<MangaRepository> {
            coEvery { getMangaByUrlAndSourceId(any(), sourceId) } returns null
        }
        Injekt.addSingleton(application)
        Injekt.addSingleton<Json>(Json)
        Injekt.addSingleton(repository)
        every { anyConstructed<LocalLibraryPreferences>().getConfig() } returns config
        every { anyConstructed<LocalLibraryPreferences>().getIndex() } returns index
        every { anyConstructed<LocalLibraryPreferences>().resolveRoot(application, root) } returns base
        every { anyConstructed<LocalLibraryPreferences>().clearPendingChapterRefresh(any()) } returns Unit
        var source: LocalFolderSource? = null
        mockkStatic(Resources::class)
        val resources = mockk<Resources>()
        every { Resources.getSystem() } returns resources
        every { resources.displayMetrics } returns DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 1920
        }
        try {
            val active =
                LocalFolderSource(
                    application,
                    sourceId,
                    "Fixture",
                    LibraryConnectionProfile(sourceId, "local", "Fixture"),
                )
            source = active
            val cover = image("cover.JPG")
            val sidecar = image("ametadata")
            assertSame(cover, active.findCover(images + sidecar + cover))
            (images + sidecar + cover).forEach { verify(exactly = 0) { it.openInputStream() } }
            val manga = SManga.create().apply {
                url = LocalLibraryLocator.seriesUrl(sourceId, root.id, locator)
                title = "Comic"
            }
            val chapter = active.getChapterList(manga).single()
            assertEquals(LocalLibraryLocator.chapterUrl(sourceId, root.id, locator), chapter.url)
            assertEquals("Comic", chapter.name)
            assertEquals(1F, chapter.chapter_number)
            assertEquals(1, active.readProgressIndex(manga.url)?.indexedChapterCount)
            assertSame(directory, active.localChapterFile(chapter.url))
            verify(exactly = 1) { anyConstructed<LocalLibraryPreferences>().clearPendingChapterRefresh(item.itemKey) }
            verify(exactly = 0) { directory.listFiles() }

            val chapterDirectory = active.localChapterFile(chapter.url)!!
            val readerPages = DirectoryPageLoader(chapterDirectory, filesProvider = {
                active.listReaderImageFiles(chapterDirectory)
            }).getPages()
            assertEquals(listOf(0, 1, 2), readerPages.map { it.index })
            images.forEach { image -> verify(exactly = 0) { image.openInputStream() } }
            assertEquals(
                listOf("1.jpg", "2.jpg", "10.jpg"),
                readerPages.map { it.stream!!().use { stream -> stream.readBytes().decodeToString() } },
            )
            verify(exactly = 1) { directory.listFiles() }
        } finally {
            source?.close()
            unmockkConstructor(LocalLibraryPreferences::class)
            unmockkStatic(Resources::class)
            Injekt = previousInjekt
        }
    }
}
