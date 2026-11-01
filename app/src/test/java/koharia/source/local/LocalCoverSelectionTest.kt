package koharia.source.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalCoverSelectionTest {
    @Test
    fun `only explicit cover filenames receive cover priority`() {
        assertTrue(isPreferredLocalCoverName("cover.jpg"))
        assertTrue(isPreferredLocalCoverName("Folder.PNG"))
        assertTrue(isPreferredLocalCoverName("!cover.webp"))
        assertFalse(isPreferredLocalCoverName("cover-design.jpg"))
        assertFalse(isPreferredLocalCoverName("coverpage.jpg"))
        assertFalse(isPreferredLocalCoverName("001.jpg"))
    }
}
