package eu.kanade.tachiyomi.ui.reader.loader

import android.content.res.Resources
import android.util.DisplayMetrics
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class DirectoryPageLoaderTest {
    @BeforeEach
    fun setUpDisplayResources() {
        mockkStatic(Resources::class)
        val resources = mockk<Resources>()
        every { Resources.getSystem() } returns resources
        every { resources.displayMetrics } returns DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 1920
        }
    }

    @AfterEach
    fun tearDownDisplayResources() {
        unmockkStatic(Resources::class)
    }

    @Test
    fun `snapshot provider loads every image in natural order without using partial directory listing`() = runTest {
        val directory = mockk<UniFile>()
        val children = listOf(image("10.jpg"), image("2.jpg"), image("1.jpg"))
        var enumerations = 0
        val loader = DirectoryPageLoader(directory, filesProvider = {
            delay(1)
            enumerations++
            children
        })

        val pages = loader.getPages()

        assertEquals(1, enumerations)
        children.forEach { child -> verify(exactly = 0) { child.openInputStream() } }
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals(List(3) { Page.State.Ready }, pages.map { it.status })
        assertEquals(
            listOf("1.jpg", "2.jpg", "10.jpg"),
            pages.map {
                it.stream!!().use { stream -> stream.readBytes().decodeToString() }
            },
        )
        verify(exactly = 0) { directory.listFiles() }
    }

    @Test
    fun `ordinary directory enumeration retains all pages and optionally excludes auxiliary images`() = runTest {
        val directory = mockk<UniFile>()
        val cover = image("cover.jpg")
        val chapter = mockk<UniFile>()
        every { chapter.name } returns "chapter.jpg"
        every { chapter.isDirectory } returns true
        every { directory.listFiles() } returns arrayOf(image("10.jpg"), cover, image("2.jpg"), chapter)

        val filtered = DirectoryPageLoader(directory, excludeAuxiliary = true).getPages()
        assertEquals(
            listOf("2.jpg", "10.jpg"),
            filtered.map {
                it.stream!!().use { stream -> stream.readBytes().decodeToString() }
            },
        )
        assertEquals(3, DirectoryPageLoader(directory).getPages().size)
        verify(exactly = 0) { chapter.openInputStream() }
    }

    @Test
    fun `snapshot enumeration failure is not replaced by a partial directory result`() {
        val directory = mockk<UniFile>()
        val loader = DirectoryPageLoader(directory, filesProvider = { throw IOException("Incomplete directory") })

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { loader.getPages() } }
        verify(exactly = 0) { directory.listFiles() }
    }

    @Test
    fun `cancelled snapshot enumeration remains cancelled without falling back`() {
        val directory = mockk<UniFile>()
        val cancellation = CancellationException("Reader closed")
        val loader = DirectoryPageLoader(directory, filesProvider = { throw cancellation })

        val thrown = assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { loader.getPages() }
        }
        assertEquals(cancellation, thrown)
        verify(exactly = 0) { directory.listFiles() }
    }

    private fun image(filename: String): UniFile = mockk {
        every { name } returns filename
        every { isDirectory } returns false
        every { openInputStream() } answers { filename.byteInputStream() }
    }
}
