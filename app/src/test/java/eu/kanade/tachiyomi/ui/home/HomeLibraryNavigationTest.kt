package eu.kanade.tachiyomi.ui.home

import koharia.connection.ConnectionLibraryKind
import koharia.connection.LibraryContentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeLibraryNavigationTest {
    @Test
    fun `mixed comic and book scopes enable separate home tabs`() {
        val navigation = homeLibraryNavigation(setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK))

        assertTrue(navigation.classificationEnabled)
        assertEquals(HomeLibraryDefaultTab.COMICS, navigation.defaultTab)
    }

    @Test
    fun `file and server kinds enable all comics and manga server tabs`() {
        val navigation = homeLibraryNavigation(
            contentScopes = setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK),
            registeredLibraryKinds = setOf(ConnectionLibraryKind.WEBDAV, ConnectionLibraryKind.SERVER),
        )

        assertFalse(navigation.classificationEnabled)
        assertTrue(navigation.sourceKindSplitEnabled)
        assertEquals(HomeLibraryDefaultTab.ALL_COMICS, navigation.defaultTab)
    }

    @Test
    fun `server kind alone does not replace the existing combined navigation`() {
        val navigation = homeLibraryNavigation(
            contentScopes = setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK),
            registeredLibraryKinds = setOf(ConnectionLibraryKind.SERVER),
        )

        assertTrue(navigation.classificationEnabled)
        assertFalse(navigation.sourceKindSplitEnabled)
        assertEquals(HomeLibraryDefaultTab.COMICS, navigation.defaultTab)
    }

    @Test
    fun `all and single media scopes keep the combined library tab`() {
        listOf(
            setOf(LibraryContentScope.ALL),
            setOf(LibraryContentScope.COMIC),
            setOf(LibraryContentScope.BOOK),
            emptySet(),
        ).forEach { scopes ->
            val navigation = homeLibraryNavigation(scopes)
            assertFalse(navigation.classificationEnabled)
            assertEquals(HomeLibraryDefaultTab.LIBRARY, navigation.defaultTab)
        }
    }
}
