package koharia.source.ehentai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EHenMetadataParserTest {
    @Test
    fun `parses matching gallery and translates tags`() {
        val json = """
            {"gmetadata":[{"gid":"42","token":"abcdef1234","title":"English","title_jpn":"日本語",
            "uploader":"user","category":"Manga","posted":"1700000000","filecount":"12","rating":"4.5",
            "thumb":"https://ehgt.org/t.jpg","tags":["language: japanese","artist: test"]}]}
        """.trimIndent()
        val result = EHenMetadataParser.parse(json, 42, "abcdef1234")
        assertEquals("English", result.title)
        assertEquals(12, result.pageCount)
        assertEquals("https://ehgt.org/t.jpg", result.coverUrl)
        assertEquals(listOf("语言: 日文", "artist: test"), result.toArchiveMetadata().tags)
        assertEquals("https://e-hentai.org/g/42/abcdef1234/", result.toArchiveMetadata().galleryUrl)
        assertEquals(
            "https://exhentai.org/g/42/abcdef1234/",
            result.toArchiveMetadata(site = EHenSite.EX_HENTAI).galleryUrl,
        )
    }

    @Test
    fun `ignores malformed tag elements without rejecting gallery metadata`() {
        val json = """
            {"gmetadata":[{"gid":"42","token":"abcdef1234","title":"English",
            "tags":["language: japanese",42,null,{"name":"invalid"}]}]}
        """.trimIndent()
        val result = EHenMetadataParser.parse(json, 42, "abcdef1234")
        assertEquals(listOf("语言: 日文"), result.toArchiveMetadata().tags)
    }

    @Test
    fun `tag sorting uses locale independent normalization`() {
        val tags = listOf("language: Japanese", "artist: Test")
        val expected = EHenTagTranslation.sort(tags, translate = false)
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals(expected, EHenTagTranslation.sort(tags, translate = false))
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `rejects a response for another gallery`() {
        val json = """{"gmetadata":[{"gid":"7","token":"abcdef1234","title":"Wrong"}]}"""
        assertThrows(IllegalStateException::class.java) {
            EHenMetadataParser.parse(json, 42, "abcdef1234")
        }
    }
}
