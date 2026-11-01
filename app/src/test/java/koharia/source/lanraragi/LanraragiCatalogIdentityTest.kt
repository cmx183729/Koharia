package koharia.source.lanraragi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanraragiCatalogIdentityTest {
    @Test
    fun `same endpoint identity is stable`() {
        val first = lanraragiCatalogIdentity("https://nas/", "http://internal/", "secret")
        val second = lanraragiCatalogIdentity("https://nas", "http://internal", "secret")

        assertTrue(hasCurrentLanraragiCatalogIdentity(first, second))
    }

    @Test
    fun `endpoint account or credential changes invalidate snapshot`() {
        val current = lanraragiCatalogIdentity("https://nas", "", "secret")

        assertNotEquals(current, lanraragiCatalogIdentity("https://other", "", "secret"))
        assertNotEquals(current, lanraragiCatalogIdentity("https://nas", "http://internal", "secret"))
        assertNotEquals(current, lanraragiCatalogIdentity("https://nas", "", "other"))
        assertFalse(hasCurrentLanraragiCatalogIdentity("", current))
        assertTrue(lanraragiCatalogIdentityChanged("", current))
    }
}
