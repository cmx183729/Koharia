package koharia.source.local

import android.net.Uri
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafUriExtensionsTest {
    @Test
    fun `content tree uri is recognized independently of provider metadata`() {
        assertTrue(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload").isSafTreeUri())
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
        assertFalse(
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload").isSafTreeUri(),
        )
        assertFalse(Uri.parse("file:///sdcard/Download").isSafTreeUri())
    }
}
