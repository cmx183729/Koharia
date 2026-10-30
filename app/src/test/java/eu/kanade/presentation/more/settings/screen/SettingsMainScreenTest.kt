package eu.kanade.presentation.more.settings.screen

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsMainScreenTest {
    private val surface = Color(0xFF101010)
    private val surfaceContainerLow = Color(0xFF1A1A1A)

    @Test
    fun `two pane settings use semantic container surface`() {
        assertEquals(
            surfaceContainerLow,
            settingsMainContainerColor(true, surface, surfaceContainerLow),
        )
    }

    @Test
    fun `single pane settings retain the base surface`() {
        assertEquals(
            surface,
            settingsMainContainerColor(false, surface, surfaceContainerLow),
        )
    }
}
