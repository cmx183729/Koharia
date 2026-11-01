package koharia.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalScanTitleTest {
    @Test
    fun `fallback title uses logical relative path and strips file extension`() {
        assertEquals("Comic Name", scanFallbackTitle("nested/Comic Name.cbz", isDirectory = false))
        assertEquals("Series Name", scanFallbackTitle("nested/Series Name", isDirectory = true))
        assertEquals(
            "Root",
            scanFallbackTitle(LocalLibraryLocator.ROOT_DIRECTORY_ENTRY, isDirectory = true, fallbackName = "Root"),
        )
    }
}
