package koharia.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsExtensionManifestTest {
    @Test
    fun `parses declared source capabilities`() {
        val manifest = JsExtensionManifestParser.parse(
            """
                {
                  "id":"example.manga",
                  "name":"Example Manga",
                  "version":"1.2.0",
                  "script_url":"https://example.org/source.js",
                  "sha256":"${"a".repeat(64)}",
                  "capabilities":["SEARCH","EXPLORE","CATEGORIES","RANKING","TAG_FILTER"],
                  "homepage":"https://example.org"
                }
            """.trimIndent(),
        )

        assertEquals("example.manga", manifest.id)
        assertEquals(5, manifest.capabilities.size)
        assertEquals(emptyList<String>(), manifest.validate())
    }

    @Test
    fun `rejects non-https script URL and invalid digest`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JsExtensionManifestParser.parse(
                """
                    {"id":"example.manga","name":"Example","version":"1",
                    "script_url":"http://example.org/source.js","sha256":"bad"}
                """.trimIndent(),
            )
        }

        assertEquals(true, error.message!!.contains("Invalid JS extension manifest"))
    }

    @Test
    fun `rejects unknown capability values`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsExtensionManifestParser.parse(
                """
                    {"id":"example.manga","name":"Example","version":"1",
                    "script_url":"https://example.org/source.js","sha256":"${"b".repeat(64)}",
                    "capabilities":["DELETE_REMOTE_FILES"]}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects duplicate capabilities and oversized manifests`() {
        val duplicate = JsExtensionManifest(
            id = "example.manga",
            name = "Example",
            version = "1",
            scriptUrl = "https://example.org/source.js",
            sha256 = "c".repeat(64),
            capabilities = listOf(
                JsExtensionManifest.Capability.SEARCH,
                JsExtensionManifest.Capability.SEARCH,
            ),
        )
        assertEquals(true, duplicate.validate().any { it.contains("duplicates") })

        assertThrows(IllegalArgumentException::class.java) {
            JsExtensionManifestParser.parse(" ".repeat(64 * 1024 + 1))
        }
    }
}
