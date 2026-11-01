package koharia.source.komga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class KomgaCacheIdentityTest {
    @Test
    fun `cache namespace preserves existing identity format for unambiguous values`() {
        val input = "1|https://komga.test/library|Credentials|reader|password|"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, namespace())
    }

    @Test
    fun `cache namespace changes with connection endpoint or authentication settings`() {
        val current = namespace()

        assertNotEquals(current, namespace(sourceId = 2))
        assertNotEquals(current, namespace(baseUrl = "https://other.test"))
        assertNotEquals(current, namespace(authMode = "ApiKey"))
        assertNotEquals(current, namespace(username = "other"))
        assertNotEquals(current, namespace(password = "other"))
        assertNotEquals(current, namespace(apiKey = "other"))
    }

    @Test
    fun `cache namespace encoding distinguishes credential values containing separators`() {
        assertNotEquals(
            namespace(username = "reader|archive", password = "key"),
            namespace(username = "reader", password = "archive|key"),
        )
    }

    @Test
    fun `cache namespace does not expose configuration values`() {
        val result = namespace(username = "fixture-user", password = "fixture-password", apiKey = "fixture-api-key")

        assertTrue(result.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals("fixture-user", result)
        assertNotEquals("fixture-password", result)
        assertNotEquals("fixture-api-key", result)
    }

    private fun namespace(
        sourceId: Long = 1,
        baseUrl: String = "https://komga.test/library",
        authMode: String = "Credentials",
        username: String = "reader",
        password: String = "password",
        apiKey: String = "",
    ) = komgaShelfCacheNamespace(sourceId, baseUrl, authMode, username, password, apiKey)
}
