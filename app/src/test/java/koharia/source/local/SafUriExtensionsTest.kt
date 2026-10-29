package koharia.source.local

import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafUriExtensionsTest {
    @Test
    fun `content tree uri is recognized independently of provider metadata`() {
        val uri = mockk<Uri> { every { scheme } returns "content" }
        mockkStatic(DocumentsContract::class)
        try {
            every { DocumentsContract.isTreeUri(uri) } returns true
            assertTrue(uri.isSafTreeUri())
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    @Test
    fun `unknown saf mime type probes children even when the display name has a dot`() {
        assertTrue(LocalScanDirectoryReader.shouldProbeDirectoryChildren(null))
        assertTrue(
            LocalScanDirectoryReader.shouldProbeDirectoryChildren("application/octet-stream"),
        )
    }

    @Test
    fun `known file mime type does not probe children`() {
        assertFalse(
            LocalScanDirectoryReader.shouldProbeDirectoryChildren("image/jpeg"),
        )
    }

    @Test
    fun `non tree uri is not treated as saf directory`() {
        val document = mockk<Uri> { every { scheme } returns "content" }
        val file = mockk<Uri> { every { scheme } returns "file" }
        mockkStatic(DocumentsContract::class)
        try {
            every { DocumentsContract.isTreeUri(document) } returns false
            assertFalse(document.isSafTreeUri())
            assertFalse(file.isSafTreeUri())
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }
}
