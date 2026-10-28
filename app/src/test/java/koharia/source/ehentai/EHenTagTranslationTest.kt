package koharia.source.ehentai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EHenTagTranslationTest {
    @Test
    fun `normalizes cookie header input`() {
        assertEquals(
            "igneous=abc; ipb_member_id=123; ipb_pass_hash=def",
            EHenCookieParser.parse("Cookie: igneous=abc; ipb_member_id=123; ipb_pass_hash=def"),
        )
    }

    @Test
    fun `extracts browser cookie export JSON`() {
        assertEquals(
            "igneous=abc; ipb_member_id=123",
            EHenCookieParser.parse("[{\"name\":\"igneous\",\"value\":\"abc\"},{\"name\":\"ipb_member_id\",\"value\":\"123\"}]"),
        )
    }

    @Test
    fun `ignores invalid cookie records in browser JSON`() {
        assertEquals(
            "igneous=abc",
            EHenCookieParser.parse("[{\"name\":\"igneous\",\"value\":\"abc\"},{\"name\":\"bad\",\"value\":true}]"),
        )
    }

    @Test
    fun `extracts browser cookie export object`() {
        assertEquals(
            "igneous=abc; ipb_member_id=123",
            EHenCookieParser.parse("{\"igneous\":\"abc\",\"ipb_member_id\":\"123\"}"),
        )
    }

    @Test
    fun `ignores metadata fields in browser cookie export object`() {
        assertEquals(
            "igneous=abc",
            EHenCookieParser.parse("{\"igneous\":\"abc\",\"httpOnly\":true,\"path\":\"/\",\"domain\":\"e-hentai.org\"}"),
        )
    }

    @Test
    fun `ignores non string fields in browser cookie export object`() {
        assertEquals(
            "igneous=abc",
            EHenCookieParser.parse("{\"igneous\":\"abc\",\"enabled\":true}"),
        )
        assertEquals("", EHenCookieParser.parse("{\"name\":true,\"value\":\"abc\"}"))
        assertEquals("", EHenCookieParser.parse("{\"name\":\"igneous\",\"value\":true}"))
    }

    @Test
    fun `classifies tags into namespaces and preserves unnamespaced tags`() {
        val language = EHenTagTranslation.classify("language: Japanese")
        val other = EHenTagTranslation.classify("  original work  ")

        assertEquals("language", language.namespace)
        assertEquals("japanese", language.name)
        assertEquals("other", other.namespace)
        assertEquals("original work", other.name)
    }

    @Test
    fun `sorts tags by stable namespace order and translates display names`() {
        try {
            EHenTagTranslation.replace(
                mapOf(
                    "female: schoolgirl uniform" to "女性: 制服",
                    "language: japanese" to "语言: 日文",
                ),
            )

            assertEquals(
                listOf("语言: 日文", "女性: 制服", "original work"),
                EHenTagTranslation.sort(listOf("female: schoolgirl uniform", "original work", "language: Japanese")),
            )
            assertEquals(
                listOf("female: schoolgirl uniform", "language: Japanese", "original work"),
                EHenTagTranslation.sort(
                    listOf("female: schoolgirl uniform", "original work", "language: Japanese"),
                    translate = false,
                ),
            )
        } finally {
            EHenTagTranslation.replace(emptyMap())
        }
    }

    @Test
    fun `cookie parser preserves equals signs inside cookie values`() {
        assertEquals(
            "igneous=abc=def; ipb_member_id=123",
            EHenCookieParser.parse("igneous=abc=def; ipb_member_id=123"),
        )
    }

    @Test
    fun `matches all required tags and excludes denied tags`() {
        val tags = listOf("language: Japanese", "female: Schoolgirl Uniform")
        assertEquals(
            true,
            EHenTagTranslation.matches(
                tags,
                required = listOf("language: japanese"),
            ),
        )
        assertEquals(
            false,
            EHenTagTranslation.matches(
                tags,
                required = listOf("language: japanese", "artist: missing"),
            ),
        )
        assertEquals(
            false,
            EHenTagTranslation.matches(
                tags,
                excluded = listOf("female: schoolgirl uniform"),
            ),
        )
    }

    @Test
    fun `empty tag filters match every item`() {
        assertEquals(true, EHenTagTranslation.matches(listOf("artist: test")))
    }

    @Test
    fun `parses translation database namespace data objects`() {
        val parsed = EHenTagTranslationDatabaseParser.parse(
            """
                {
                  "head":{"committer":{"when":"2026-10-28T14:36:55.000Z"}},
                  "data":[
                    {"namespace":"language","count":1,"data":{"afrikaans":{"name":"<p>南非语</p>","intro":"description","links":""}}},
                    {"namespace":"female","data":{"schoolgirl uniform":{"name":"<p>女学生制服</p>"}}}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("2026-10-28T14:36:55.000Z", parsed.timestamp)
        assertEquals("语言: 南非语", parsed.entries["language: afrikaans"])
        assertEquals("女性: 女学生制服", parsed.entries["female: schoolgirl uniform"])
    }

    @Test
    fun `skips malformed entries while parsing translation database`() {
        val parsed = EHenTagTranslationDatabaseParser.parse(
            """
                {"data":[
                  {"namespace":"language","data":{"valid":{"name":"<p>有效</p>"},"invalid":false}},
                  {"namespace":"female","data":{"missing_name":{"intro":"no name"}}},
                  {"namespace":"","data":{"ignored":{"name":"ignored"}}}
                ]}
            """.trimIndent(),
        )

        assertEquals(mapOf("language: valid" to "语言: 有效"), parsed.entries)
    }

    @Test
    fun `normalizes downloaded translation keys`() {
        try {
            EHenTagTranslation.replace(mapOf("  artist:  test-normalization  " to "艺术家：测试"))

            assertEquals("艺术家：测试", EHenTagTranslation.toChinese("artist: test-normalization"))
            assertEquals("untranslated: value", EHenTagTranslation.toChinese(" untranslated:   value "))
        } finally {
            EHenTagTranslation.replace(emptyMap())
        }
    }
}
