package koharia.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
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
    /** When the gallery itself was published, as written by the archiver. */
    val publishTime: String? = null,
    /** When the archiver stored this copy. Only the newer sidecars carry it. */
    val insertTime: String? = null,
    /** Direct archive download the archiver recorded, when it still exists. */
    val downloadUrl: String? = null,
) {
    val hasUsefulIdentity: Boolean get() = !title.isNullOrBlank() || !series.isNullOrBlank() || gid != null
}

object ArchiveMetadataParser {
    private val gidToken = Regex("/g/(\\d+)/(\\p{XDigit}{10,})/?", RegexOption.IGNORE_CASE)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun comicInfo(xml: ByteArray): ArchiveMetadata {
        require(xml.size <= MAX_METADATA_BYTES) { "ComicInfo.xml exceeds the supported size" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        fun text(name: String) = document.getElementsByTagName(name).item(0)?.textContent.clean()
        val web = text("Web")
        val link = web?.let(gidToken::find)
        val tags = splitValues(text("Tags"))
        val characters = splitValues(text("Characters"))
        // The archiver writes the artist only as a namespaced tag, and never fills Writer/Penciller.
        val namespacedArtists = valuesInNamespace(tags, ARTIST_NAMESPACE)
        return ArchiveMetadata(
            title = text("Title"), series = text("Series"),
            authors = (
                listOfNotNull(text("Writer"), text("Penciller"), text("Artist")).flatMap(::splitValues) +
                    namespacedArtists
                ).distinct(),
            genres = splitValues(text("Genre")),
            // Characters ride along as namespaced tags so they sort with the other gallery tags.
            tags = tags + characters.map { "$CHARACTER_NAMESPACE:$it" },
            pageCount = text("PageCount")?.toIntOrNull(), rating = text("CommunityRating")?.toDoubleOrNull(),
            galleryUrl = web,
            gid = link?.groupValues?.get(1)?.toLongOrNull(), token = link?.groupValues?.get(2),
        )
    }

    fun ametadata(input: InputStream): ArchiveMetadata = ametadata(readBounded(input, "ametadata"))

    fun ametadata(bytes: ByteArray): ArchiveMetadata {
        require(bytes.size <= MAX_METADATA_BYTES) { "ametadata exceeds the supported size" }
        val root = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        val tags = splitValues(root.text("tags"))
        val url = root.text("galleryUrl") ?: root.text("gallery_url")
        val link = url?.let(gidToken::find)
        return ArchiveMetadata(
            title = root.text("title"),
            authors = valuesInNamespace(tags, ARTIST_NAMESPACE),
            genres = splitValues(root.text("category")), tags = tags,
            pageCount = root.text("pageCount")?.toIntOrNull(), galleryUrl = url,
            coverUrl = root.text("coverUrl") ?: root.text("cover_url"),
            uploader = root.text("uploader"),
            publishTime = root.text("publishTime") ?: root.text("publish_time"),
            insertTime = root.text("insertTime") ?: root.text("insert_time"),
            downloadUrl = root.text("downloadUrl") ?: root.text("download_url"),
            gid = root.text("gid")?.toLongOrNull() ?: link?.groupValues?.get(1)?.toLongOrNull(),
            token = root.text("token") ?: link?.groupValues?.get(2),
        )
    }

    /**
     * Reads the legacy sidecar the older E-Hentai archivers wrote: both the gallery fields and the
     * per-page download table live in one object, and only the gallery half carries anything usable.
     */
    fun legacyMetadata(input: InputStream): ArchiveMetadata = legacyMetadata(readBounded(input, "metadata"))

    fun legacyMetadata(bytes: ByteArray): ArchiveMetadata {
        require(bytes.size <= MAX_METADATA_BYTES) { "metadata exceeds the supported size" }
        val root = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        val gallery = root["gallery"] as? JsonObject ?: root
        val tags = splitValues(gallery.text("tags"))
        val url = gallery.text("galleryUrl") ?: gallery.text("gallery_url")
        val link = url?.let(gidToken::find)
        return ArchiveMetadata(
            title = gallery.text("title"),
            authors = valuesInNamespace(tags, ARTIST_NAMESPACE),
            genres = splitValues(gallery.text("category")), tags = tags,
            pageCount = gallery.text("pageCount")?.toIntOrNull(), galleryUrl = url,
            uploader = gallery.text("uploader"),
            publishTime = gallery.text("publishTime") ?: gallery.text("publish_time"),
            insertTime = gallery.text("insertTime") ?: gallery.text("insert_time"),
            gid = gallery.text("gid")?.toLongOrNull() ?: link?.groupValues?.get(1)?.toLongOrNull(),
            token = gallery.text("token") ?: link?.groupValues?.get(2),
        )
    }

    private fun readBounded(input: InputStream, label: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_METADATA_BYTES) { "$label exceeds the supported size" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** A JSON null reads back as the text "null" through [JsonPrimitive.content], so filter it out. */
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.clean()

    private fun String?.clean() = this?.replace(Regex("[\\u0000-\\u001f]"), "")?.trim()?.takeIf(String::isNotEmpty)

    private fun splitValues(
        value: String?,
    ) = value?.split(',', ';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    private fun valuesInNamespace(values: List<String>, namespace: String) = values.mapNotNull { value ->
        val separator = value.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        if (!value.substring(0, separator).equals(namespace, ignoreCase = true)) return@mapNotNull null
        value.substring(separator + 1).trim().takeIf(String::isNotEmpty)
    }

    private const val ARTIST_NAMESPACE = "artist"
    private const val CHARACTER_NAMESPACE = "character"
    private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
}
