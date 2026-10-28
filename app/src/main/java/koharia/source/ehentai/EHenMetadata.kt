package koharia.source.ehentai

import koharia.scan.ArchiveMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One gallery returned by the E-Hentai gmetadata endpoint. */
data class EHenGalleryMetadata(
    val gid: Long,
    val token: String,
    val title: String,
    val titleJapanese: String?,
    val uploader: String?,
    val category: String?,
    val posted: String?,
    val pageCount: Int?,
    val rating: Double?,
    val coverUrl: String?,
    val tags: List<String>,
) {
    fun toArchiveMetadata(translateTags: Boolean = true) = ArchiveMetadata(
        title = title,
        authors = emptyList(),
        genres = listOfNotNull(category),
        tags = if (translateTags) tags.map(EHenTagTranslation::toChinese) else tags,
        pageCount = pageCount,
        rating = rating,
        gid = gid,
        token = token,
        galleryUrl = "https://e-hentai.org/g/$gid/$token/",
        coverUrl = coverUrl,
        uploader = uploader,
        publishTime = posted,
    )
}

object EHenMetadataParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, expectedGid: Long, expectedToken: String): EHenGalleryMetadata {
        require(text.length <= MAX_RESPONSE_CHARS) { "E-Hentai metadata response exceeds size limit" }
        val root = json.parseToJsonElement(text).jsonObject
        val galleries = root["gmetadata"]?.jsonArray ?: error("Missing gmetadata response")
        val gallery = galleries.map { it.jsonObject }.firstOrNull {
            it["gid"]?.jsonPrimitive?.content?.toLongOrNull() == expectedGid &&
                it["token"]?.jsonPrimitive?.content == expectedToken
        } ?: error("Requested gallery was not returned")
        fun value(key: String) = gallery[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val tags = gallery["tags"]?.jsonArray.orEmpty().mapNotNull { element ->
            element.jsonPrimitive.content.takeIf(String::isNotBlank)
        }.distinct()
        return EHenGalleryMetadata(
            gid = expectedGid,
            token = expectedToken,
            title = value("title") ?: value("title_jpn") ?: "",
            titleJapanese = value("title_jpn"),
            uploader = value("uploader"),
            category = value("category"),
            posted = value("posted"),
            pageCount = value("filecount")?.toIntOrNull(),
            rating = value("rating")?.toDoubleOrNull(),
            coverUrl = value("thumb")?.let(::safeImageUrl),
            tags = tags,
        )
    }

    private fun safeImageUrl(value: String): String? = runCatching {
        value.toHttpUrl().takeIf { url ->
            url.isHttps && IMAGE_HOSTS.any { host -> url.host == host || url.host.endsWith(".$host") }
        }?.toString()
    }.getOrNull()

    private val IMAGE_HOSTS = listOf("e-hentai.org", "exhentai.org", "ehgt.org")

    private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
}

object EHenTagTranslation {
    private val common = mapOf(
        "language: japanese" to "语言: 日文",
        "language: chinese" to "语言: 中文",
        "language: english" to "语言: 英文",
        "language: translated" to "语言: 汉化",
        "language: english translated" to "语言: 英译",
        "female: big breasts" to "女性: 巨乳",
        "female: small breasts" to "女性: 贫乳",
        "female: schoolgirl uniform" to "女性: 制服",
        "female: uncensored" to "女性: 无修正",
        "male: big penis" to "男性: 大屌",
        "artist: original" to "艺术家: 原创",
    )

    fun toChinese(tag: String): String {
        val normalized = tag.trim().replace(Regex("\\s+"), " ")
        return common[normalized.lowercase()] ?: normalized
    }
}
