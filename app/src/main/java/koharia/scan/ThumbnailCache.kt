package koharia.scan

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Generated derivatives only; source covers, manual downloads and reader caches stay untouched. */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val createThumbnail: (File, Int, Int, Boolean) -> Bitmap? = ::decodeThumbnail,
) {
    private val lock = Any()

    fun get(key: String): File? = synchronized(lock) {
        fileFor(key).takeIf { it.isFile && it.length() > 0L }?.also {
            it.setLastModified(System.currentTimeMillis())
        }
    }

    fun put(key: String, bitmap: Bitmap, quality: Int = 82): File = synchronized(lock) {
        check(directory.mkdirs() || directory.isDirectory)
        val target = fileFor(key)
        val temporary = File.createTempFile("thumbnail-", ".part", directory)
        try {
            FileOutputStream(temporary).use { output ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                check(bitmap.compress(format, quality.coerceIn(60, 95), output))
                output.fd.sync()
            }
            check(temporary.length() in 1..maxBytes) { "Thumbnail exceeds cache budget" }
            check(temporary.renameTo(target)) { "Unable to promote thumbnail" }
            target.setLastModified(System.currentTimeMillis())
            evict(target)
            target
        } finally {
            temporary.delete()
        }
    }

    fun openOrCreate(
        source: File,
        identity: String,
        width: Int,
        height: Int,
        fill: Boolean,
        allowCreate: Boolean,
    ): InputStream? = synchronized(lock) {
        if (!source.isFile || width <= 0 || height <= 0) return null
        val length = source.length()
        val modified = source.lastModified()
        val key = "$identity;${source.canonicalPath};$length;$modified;$width;$height;$fill;v1"
        // Open under the eviction lock; a later eviction cannot invalidate an open descriptor.
        get(key)?.let { return it.inputStream() }
        if (!allowCreate) return null
        val bitmap = try {
            createThumbnail(source, width, height, fill)
        } catch (_: OriginalRequired) {
            return null
        } ?: return null
        try {
            // An in-flight cover refresh must not publish a derivative of obsolete bytes.
            if (source.length() != length || source.lastModified() != modified) return null
            put(key, bitmap).inputStream()
        } finally {
            bitmap.recycle()
        }
    }

    private class OriginalRequired : RuntimeException()

    fun size(): Long = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    }

    fun removeAll(): Int = synchronized(lock) {
        directory.listFiles().orEmpty().count { it.isFile && it.delete() }
    }

    private fun fileFor(key: String) = File(directory, digest(key) + ".webp")

    private fun evict(protected: File) {
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "webp" }
        var used = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (used <= maxBytes) break
            val size = file.length()
            if (file != protected && file.delete()) used -= size
        }
    }

    internal data class Plan(val width: Int, val height: Int)

    companion object {
        private const val MAX_EDGE = 1024

        internal fun plan(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int, fill: Boolean): Plan? {
            if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return null
            val scale = min(1.0, MAX_EDGE.toDouble() / max(sourceWidth, sourceHeight))
            val requested = if (fill) {
                max(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
            } else {
                min(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
            }
            // Large/detail/original requests retain the original rather than upscale a thumbnail.
            if (scale < min(1.0, requested)) return null
            val targetWidth = max(1, (sourceWidth * scale).roundToInt())
            val targetHeight = max(1, (sourceHeight * scale).roundToInt())
            return Plan(targetWidth, targetHeight)
        }

        private fun decodeThumbnail(source: File, width: Int, height: Int, fill: Boolean): Bitmap? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return decodePlatformThumbnail(source, width, height, fill)
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun decodePlatformThumbnail(source: File, width: Int, height: Int, fill: Boolean): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                if (info.isAnimated) throw OriginalRequired()
                val plan = plan(info.size.width, info.size.height, width, height, fill) ?: throw OriginalRequired()
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(plan.width, plan.height)
            }

        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
