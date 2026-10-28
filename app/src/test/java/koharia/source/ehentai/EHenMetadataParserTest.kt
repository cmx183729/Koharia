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
    }

    @Test
    fun `rejects a response for another gallery`() {
        val json = """{"gmetadata":[{"gid":"7","token":"abcdef1234","title":"Wrong"}]}"""
        assertThrows(IllegalStateException::class.java) {
            EHenMetadataParser.parse(json, 42, "abcdef1234")
        }
    }
}
