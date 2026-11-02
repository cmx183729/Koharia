package koharia.source.local

import koharia.scan.ArchiveMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The part of an E-Hentai sidecar that is worth carrying out of the scan.
 *
 * The sidecars are the only place a local shelf can learn where a gallery came from and how old it
 * is. The library index has no column for either, and titles are already taken, so this rides in
 * [tachiyomi.domain.manga.model.Manga.memo] the same way other sources keep their own state there.
 */
data class LocalGalleryMetadata(
    val url: String? = null,
    /** Raw "2026-10-14 04:29" as written by the archiver. */
    val publishedAt: String? = null,
    /** Raw "2026-10-17 16:03:45.183576" as written by the archiver. */
    val archivedAt: String? = null,
    val pageCount: Int? = null,
    val downloadUrl: String? = null,
) {
    val isEmpty: Boolean get() = url == null && publishedAt == null && archivedAt == null && pageCount == null

    /** The date the gallery itself was released; the archiver writes no timezone, so this is local time. */
    val publishedDate: LocalDate? get() = parseLocalDateTime(publishedAt)?.toLocalDate()

    val archivedDate: LocalDate? get() = parseLocalDateTime(archivedAt)?.toLocalDate()

    /** Age in whole days, counted from whichever date the sidecar has, for the shelf badge. */
    fun ageInDays(today: LocalDate = LocalDate.now()): Long? =
        (publishedDate ?: archivedDate)?.let { ChronoUnit.DAYS.between(it, today) }

    fun toMemo(memo: JsonObject): JsonObject {
        val updated = memo.toMutableMap()
        // Clear the previous values first so a sidecar that lost a field cannot leave a stale one.
        updated.remove(KEY_URL)
        updated.remove(KEY_PUBLISHED)
        updated.remove(KEY_ARCHIVED)
        updated.remove(KEY_PAGES)
        updated.remove(KEY_DOWNLOAD)
        url.toJsonOrNull()?.let { updated[KEY_URL] = it }
        publishedAt.toJsonOrNull()?.let { updated[KEY_PUBLISHED] = it }
        archivedAt.toJsonOrNull()?.let { updated[KEY_ARCHIVED] = it }
        pageCount.toJsonOrNull()?.let { updated[KEY_PAGES] = it }
        downloadUrl.toJsonOrNull()?.let { updated[KEY_DOWNLOAD] = it }
        return JsonObject(updated)
    }

    companion object {
        const val KEY_URL = "localGalleryUrl"
        const val KEY_PUBLISHED = "localGalleryPublishedAt"
        const val KEY_ARCHIVED = "localGalleryArchivedAt"
        const val KEY_PAGES = "localGalleryPageCount"
        const val KEY_DOWNLOAD = "localGalleryDownloadUrl"

        fun fromMemo(memo: JsonObject): LocalGalleryMetadata? {
            fun text(key: String) = (memo[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf(String::isNotBlank)
            val metadata = LocalGalleryMetadata(
                url = text(KEY_URL),
                publishedAt = text(KEY_PUBLISHED),
                archivedAt = text(KEY_ARCHIVED),
                pageCount = text(KEY_PAGES)?.toIntOrNull(),
                downloadUrl = text(KEY_DOWNLOAD),
            )
            return metadata.takeUnless { it.isEmpty }
        }

        private fun parseLocalDateTime(raw: String?): LocalDateTime? {
            val text = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val normalized = text.replace(' ', 'T')
            return runCatching { LocalDateTime.parse(normalized) }.getOrNull()
                ?: runCatching { LocalDate.parse(normalized.substringBefore('T')).atStartOfDay() }.getOrNull()
        }
    }
}

internal fun ArchiveMetadata.toLocalGalleryMetadata(): LocalGalleryMetadata? = LocalGalleryMetadata(
    url = galleryUrl,
    publishedAt = publishTime,
    archivedAt = insertTime,
    pageCount = pageCount,
    downloadUrl = downloadUrl,
).takeUnless { it.isEmpty }

private fun String?.toJsonOrNull(): JsonPrimitive? = this?.takeIf(String::isNotBlank)?.let(::JsonPrimitive)
private fun Int?.toJsonOrNull(): JsonPrimitive? = this?.let(::JsonPrimitive)
