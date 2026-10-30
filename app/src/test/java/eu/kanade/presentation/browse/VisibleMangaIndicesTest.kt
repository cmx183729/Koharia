package eu.kanade.presentation.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisibleMangaIndicesTest {
    @Test
    fun `list leading slot is excluded from visible manga indices`() {
        assertEquals(listOf(0, 1), visibleMangaIndices(listOf(0, 1, 2), leadingItems = 1, itemCount = 3))
    }

    @Test
    fun `grid headers and out of range indices are excluded`() {
        assertEquals(listOf(0, 2), visibleMangaIndices(listOf(0, 1, 3, 5), leadingItems = 3, itemCount = 3))
    }
}
