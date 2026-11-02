package koharia.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveMetadataParserTest {
    @Test
    fun `parses comic info fields and gallery identity`() {
        val xml = """
            <ComicInfo><Title>Chapter</Title><Series>Series</Series><Writer>A, B</Writer><Genre>Drama</Genre>
            <Tags>language:chinese,artist:test</Tags><PageCount>24</PageCount><CommunityRating>4.5</CommunityRating>
            <Web>https://e-hentai.org/g/3882919/fb22a6afc1/</Web></ComicInfo>
        """.trimIndent().encodeToByteArray()
        val result = ArchiveMetadataParser.comicInfo(xml)
        assertEquals("Series", result.series)
        // The archiver records the artist only as a namespaced tag, so it joins Writer/Penciller.
        assertEquals(listOf("A", "B", "test"), result.authors)
        assertEquals(3882919L, result.gid)
        assertEquals("fb22a6afc1", result.token)
        assertEquals("https://e-hentai.org/g/3882919/fb22a6afc1/", result.galleryUrl)
        assertEquals(24, result.pageCount)
    }

    @Test
    fun `parses the legacy sidecar the older archivers wrote`() {
        val bytes = """
            {"gallery":{"gid":822569,"token":"bdc7ea308c","title":"Sample","category":"Manga",
            "pageCount":16,"galleryUrl":"https://e-hentai.org/g/822569/bdc7ea308c/","uploader":"someone",
            "publishTime":"2015-06-08 01:49","insertTime":"2023-12-26 23:47:28.402758"},"images":"[]"}
        """.trimIndent().encodeToByteArray()

        val result = ArchiveMetadataParser.legacyMetadata(bytes)
        assertEquals("Sample", result.title)
        assertEquals(listOf("Manga"), result.genres)
        assertEquals(16, result.pageCount)
        assertEquals(822569L, result.gid)
        assertEquals("bdc7ea308c", result.token)
        assertEquals("2015-06-08 01:49", result.publishTime)
        assertEquals("2023-12-26 23:47:28.402758", result.insertTime)
        assertTrue(result.authors.isEmpty())
    }

    @Test
    fun `reads the artist and character namespaces out of a gallery tag list`() {
        val result = ArchiveMetadataParser.ametadata(
            """{"title":"Tagged","tags":"language:chinese,artist:someone,character:ganyu,parody:x"}"""
                .encodeToByteArray(),
        )
        assertEquals(listOf("someone"), result.authors)
        assertEquals(4, result.tags.size)
    }

    @Test
    fun `maps comic info characters onto namespaced tags`() {
        val xml = """
            <ComicInfo><Series>Series</Series><Characters>ganyu, shenhe</Characters>
            <Tags>language:chinese</Tags></ComicInfo>
        """.trimIndent().encodeToByteArray()

        val result = ArchiveMetadataParser.comicInfo(xml)
        assertEquals(listOf("language:chinese", "character:ganyu", "character:shenhe"), result.tags)
    }

    @Test
    fun `does not read a json null back as text`() {
        val result = ArchiveMetadataParser.ametadata(
            """{"title":"Nulls","uploader":null,"downloadUrl":null,"cover_url":null}""".encodeToByteArray(),
        )
        assertEquals("Nulls", result.title)
        assertNull(result.uploader)
        assertNull(result.downloadUrl)
        assertNull(result.coverUrl)
    }

    @Test
    fun `bounds legacy metadata streams before parsing`() {
        assertEquals(
            "Sample",
            ArchiveMetadataParser.legacyMetadata("""{"gallery":{"title":"Sample"}}""".encodeToByteArray().inputStream())
                .title,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveMetadataParser.legacyMetadata(ByteArray(2 * 1024 * 1024 + 1).inputStream())
        }
    }

    @Test
    fun `rejects doctypes in comic info metadata`() {
        val xml = """
            <!DOCTYPE ComicInfo [<!ENTITY external SYSTEM "file:///does-not-exist">]>
            <ComicInfo><Title>&external;</Title></ComicInfo>
        """.trimIndent().encodeToByteArray()

        assertThrows(Exception::class.java) {
            ArchiveMetadataParser.comicInfo(xml)
        }
    }

    @Test
    fun `bounds ametadata streams before parsing`() {
        val bytes = """{"title":"Stream gallery","tags":"artist:test"}""".encodeToByteArray()
        assertEquals("Stream gallery", ArchiveMetadataParser.ametadata(bytes.inputStream()).title)
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveMetadataParser.ametadata(ByteArray(2 * 1024 * 1024 + 1).inputStream())
        }
    }

    @Test
    fun `parses ametadata and rejects oversized metadata`() {
        val result = ArchiveMetadataParser.ametadata(
            """{"gid":42,"token":"abcdef1234","title":"Test","category":"Manga","pageCount":12,
            "coverUrl":"https://example.test/a.webp","tags":"artist:a,language:chinese"}
            """.trimIndent().encodeToByteArray(),
        )
        assertEquals(42L, result.gid)
        assertEquals("https://example.test/a.webp", result.coverUrl)
        assertEquals(12, result.pageCount)
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveMetadataParser.ametadata(ByteArray(2 * 1024 * 1024 + 1))
        }
    }
}
