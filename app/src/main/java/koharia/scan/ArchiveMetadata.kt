package koharia.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata extracted without downloading an entire archive. */
data class ArchiveMetadata(
    val title: String? = null,
    val series: String? = null,
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val pageCount: Int? = null,
    val rating: Double? = null,
    val gid: Long? = null,
    val token: String? = null,
    val galleryUrl: String? = null,
    val coverUrl: String? = null,
    val uploader: String? = null,
    val publishTime: String? = null,
) {
    val hasUsefulIdentity: Boolean get() = !title.isNullOrBlank() || !series.isNullOrBlank() || gid != null
}

object ArchiveMetadataParser {
    private val gidToken = Regex("/g/(\\d+)/(\\p{XDigit}{10,})/?", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun comicInfo(xml: ByteArray): ArchiveMetadata {
        require(xml.size <= MAX_METADATA_BYTES) { "ComicInfo.xml exceeds the supported size" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        fun text(name: String) = document.getElementsByTagName(name).item(0)?.textContent.clean()
        val web = text("Web")
        val link = web?.let(gidToken::find)
        return ArchiveMetadata(
            title = text("Title"), series = text("Series"),
            authors = listOfNotNull(text("Writer"), text("Penciller")).flatMap(::splitValues),
            genres = splitValues(text("Genre")), tags = splitValues(text("Tags")),
            pageCount = text("PageCount")?.toIntOrNull(), rating = text("CommunityRating")?.toDoubleOrNull(),
            gid = link?.groupValues?.get(1)?.toLongOrNull(), token = link?.groupValues?.get(2),
        )
    }

    fun ametadata(bytes: ByteArray): ArchiveMetadata {
        require(bytes.size <= MAX_METADATA_BYTES) { "ametadata exceeds the supported size" }
        val root = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        fun value(name: String) = root[name]?.jsonPrimitive?.content?.clean()
        val url = value("galleryUrl") ?: value("gallery_url")
        val link = url?.let(gidToken::find)
        return ArchiveMetadata(
            title = value("title"), genres = splitValues(value("category")), tags = splitValues(value("tags")),
            pageCount = value("pageCount")?.toIntOrNull(), galleryUrl = url, coverUrl = value("coverUrl") ?: value("cover_url"),
            uploader = value("uploader"), publishTime = value("publishTime") ?: value("publish_time"),
            gid = value("gid")?.toLongOrNull() ?: link?.groupValues?.get(1)?.toLongOrNull(),
            token = value("token") ?: link?.groupValues?.get(2),
        )
    }

    private fun String?.clean() = this?.replace(Regex("[\\u0000-\\u001f]"), "")?.trim()?.takeIf(String::isNotEmpty)
    private fun splitValues(value: String?) = value?.split(',', ';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
}
