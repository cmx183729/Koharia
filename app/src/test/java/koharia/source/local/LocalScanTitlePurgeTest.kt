package koharia.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalScanTitlePurgeTest {

    @Test
    fun `blank pattern keeps the derived title`() {
        assertEquals("Some Title", purgeLocalTitle("Some Title", ""))
        assertEquals("Some Title", purgeLocalTitle("Some Title", "   "))
    }

    @Test
    fun `uncompilable pattern keeps the derived title`() {
        assertEquals("Some Title", purgeLocalTitle("Some Title", "[unclosed"))
    }

    @Test
    fun `pattern strips a release group prefix`() {
        assertEquals("Some Title", purgeLocalTitle("[Group] Some Title", "^\\[[^\\]]*\\]\\s*"))
    }

    @Test
    fun `pattern strips a trailing volume suffix and collapses the gap`() {
        assertEquals("Some Title", purgeLocalTitle("Some Title  v02", "\\s*v\\d+$"))
    }

    @Test
    fun `a pattern that would erase the title falls back to the original`() {
        assertEquals("Some Title", purgeLocalTitle("Some Title", ".*"))
    }

    @Test
    fun `a pattern that matches nothing leaves the title alone`() {
        assertEquals("Some Title", purgeLocalTitle("Some Title", "^\\[never\\]"))
    }

    @Test
    fun `blank title is returned untouched`() {
        assertEquals("", purgeLocalTitle("", "^x"))
    }

    @Test
    fun `scan fallback title still strips the extension before the pattern runs`() {
        val derived = scanFallbackTitle("library/group/Some Title.cbz", isDirectory = false)
        assertEquals("Some Title", purgeLocalTitle(derived, "^group\\s*"))
    }
}
