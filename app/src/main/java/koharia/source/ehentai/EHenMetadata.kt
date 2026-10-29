package koharia.source.ehentai

import koharia.scan.ArchiveMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

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
    fun toArchiveMetadata(
        translateTags: Boolean = true,
        site: EHenSite = EHenSite.E_HENTAI,
    ) = ArchiveMetadata(
        title = title,
        authors = emptyList(),
        genres = listOfNotNull(category),
        tags = EHenTagTranslation.sort(tags, translateTags),
        pageCount = pageCount,
        rating = rating,
        gid = gid,
        token = token,
        galleryUrl = "https://${site.host}/g/$gid/$token/",
        coverUrl = coverUrl,
        uploader = uploader,
        publishTime = posted,
    )
}

object EHenMetadataParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String, expectedGid: Long, expectedToken: String): EHenGalleryMetadata {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "E-Hentai metadata response exceeds size limit"
        }
        val root = json.parseToJsonElement(text).jsonObject
        val galleries = root["gmetadata"]?.jsonArray ?: error("Missing gmetadata response")
        val gallery = galleries.map { it.jsonObject }.firstOrNull {
            it["gid"]?.jsonPrimitive?.content?.toLongOrNull() == expectedGid &&
                it["token"]?.jsonPrimitive?.content == expectedToken
        } ?: error("Requested gallery was not returned")
        fun value(key: String) = gallery[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val tags = gallery["tags"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching { element.jsonPrimitive.content }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
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

    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
}

data class EHenTag(
    val namespace: String,
    val name: String,
    val displayName: String,
)

object EHenTagTranslation {
    private val fallback = mapOf(
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

    @Volatile private var translations: Map<String, String> = fallback

    fun replace(entries: Map<String, String>) {
        translations = fallback + entries.mapKeys { normalize(it.key) }
    }

    fun toChinese(tag: String): String {
        val normalized = normalize(tag)
        return translations[normalized] ?: tag.trim().replace(Regex("\\s+"), " ")
    }

    fun classify(tag: String, translate: Boolean = true): EHenTag {
        val normalized = normalize(tag)
        val separator = normalized.indexOf(':')
        val namespace = if (separator > 0) normalized.substring(0, separator) else "other"
        val name = if (separator > 0) normalized.substring(separator + 1).trim() else normalized
        return EHenTag(
            namespace = namespace,
            name = name,
            displayName = if (translate) translations[normalized] ?: tag.trim() else tag.trim(),
        )
    }

    fun sort(tags: List<String>, translate: Boolean = true): List<String> = tags
        .map { classify(it, translate) }
        .sortedWith(compareBy<EHenTag>({ namespaceOrder(it.namespace) }, { it.namespace }, { it.name }))
        .map(EHenTag::displayName)
        .distinct()

    /** Returns true when every required tag is present and no excluded tag is present. */
    fun matches(
        tags: Iterable<String>,
        required: Iterable<String> = emptyList(),
        excluded: Iterable<String> = emptyList(),
    ): Boolean {
        val available = tags.map(::normalize).filter(String::isNotBlank).toSet()
        val mustHave = required.map(::normalize).filter(String::isNotBlank).toSet()
        val mustNotHave = excluded.map(::normalize).filter(String::isNotBlank).toSet()
        return mustHave.all(available::contains) && mustNotHave.none(available::contains)
    }

    private fun namespaceOrder(namespace: String): Int = when (namespace) {
        "language" -> 0
        "artist" -> 1
        "group" -> 2
        "parody" -> 3
        "character" -> 4
        "female" -> 5
        "male" -> 6
        "mixed" -> 7
        "cosplayer" -> 8
        "reclass" -> 9
        "temp" -> 10
        "other" -> 11
        else -> 12
    }

    private fun normalize(tag: String) = tag.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
