package koharia.source.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class LocalScanDirectoryReaderTest {
    @Test
    fun `repeated enumeration reuses a scan snapshot without restatting pages`() {
        val pages = (1..500).map { file("$it.jpg", directory = false) }
        val directory = file("comic", directory = true)
        every { directory.listFiles() } returns pages.toTypedArray()
        val reader = LocalScanDirectoryReader(mockk<Context>())

        val first = reader.list(directory)
        assertEquals(first, reader.list(directory))
        assertEquals(500, first.size)
        pages.forEach { reader.metadata(it) }

        verify(exactly = 1) { directory.listFiles() }
        pages.forEach {
            verify(exactly = 1) { it.name }
            verify(exactly = 1) { it.isDirectory }
            verify(exactly = 1) { it.length() }
            verify(exactly = 1) { it.lastModified() }
            verify(exactly = 0) { it.openInputStream() }
        }
    }

    @Test
    fun `failed enumeration is not cached as an empty directory`() {
        val directory = file("comic", directory = true)
        val page = file("1.jpg", directory = false)
        every { directory.listFiles() } returnsMany listOf(null, arrayOf(page))
        val reader = LocalScanDirectoryReader(mockk<Context>())

        assertThrows(IOException::class.java) { reader.list(directory) }
        assertEquals(listOf("1.jpg"), reader.list(directory).map { it.name })
        verify(exactly = 2) { directory.listFiles() }
    }

    @Test
    fun `new scan reads changed contents instead of reusing a previous snapshot`() {
        val directory = file("comic", directory = true)
        val firstPage = file("1.jpg", directory = false)
        val secondPage = file("2.jpg", directory = false)
        every { directory.listFiles() } returnsMany listOf(arrayOf(firstPage), arrayOf(firstPage, secondPage))
        val context = mockk<Context>()
        val firstScan = LocalScanDirectoryReader(context)

        assertEquals(1, firstScan.list(directory).size)
        assertEquals(1, firstScan.list(directory).size)
        assertEquals(2, LocalScanDirectoryReader(context).list(directory).size)
        verify(exactly = 2) { directory.listFiles() }
    }

    private fun file(filename: String, directory: Boolean): UniFile = mockk {
        every { uri } returns mockk<Uri> { every { scheme } returns "fixture" }
        every { name } returns filename
        every { isDirectory } returns directory
        every { length() } returns 100
        every { lastModified() } returns 123
    }
}
