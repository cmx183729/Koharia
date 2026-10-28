package koharia.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ArchiveMetadataParserTest {
    @Test
    fun `parses comic info fields and gallery identity`() {
        val xml = """<ComicInfo><Title>Chapter</Title><Series>Series</Series><Writer>A, B</Writer><Genre>Drama</Genre><Tags>language:chinese,artist:test</Tags><PageCount>24</PageCount><CommunityRating>4.5</CommunityRating><Web>https://e-hentai.org/g/3882919/fb22a6afc1/</Web></ComicInfo>""".encodeToByteArray()
        val result = ArchiveMetadataParser.comicInfo(xml)
        assertEquals("Series", result.series)
        assertEquals(listOf("A", "B"), result.authors)
        assertEquals(3882919L, result.gid)
        assertEquals("fb22a6afc1", result.token)
        assertEquals(24, result.pageCount)
    }

    @Test
    fun `parses ametadata and rejects oversized metadata`() {
        val result = ArchiveMetadataParser.ametadata("""{"gid":42,"token":"abcdef1234","title":"Test","category":"Manga","pageCount":12,"coverUrl":"https://example.test/a.webp","tags":"artist:a,language:chinese"}""".encodeToByteArray())
        assertEquals(42L, result.gid)
        assertEquals("https://example.test/a.webp", result.coverUrl)
        assertEquals(12, result.pageCount)
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveMetadataParser.ametadata(ByteArray(2 * 1024 * 1024 + 1))
        }
    }
}
