package koharia.scan

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Generated derivatives only; source covers, manual downloads and reader caches stay untouched. */
class ThumbnailCache(
    private val directory: File,
    private val maxBytes: Long,
    private val createThumbnail: (File, Int, Int, Boolean) -> Bitmap? = ::decodeThumbnail,
) {
    /** Guards the index below. Never held while decoding, encoding or writing a thumbnail. */
    private val lock = Any()

    /** File name to the moment it last mattered; larger is newer. Seeded from the files on disk. */
    private val recency = HashMap<String, Long>()
    private var sequence = 0L
    private var usedBytes = -1L

    /** One gate per generated key, so duplicate requests decode once without serializing keys. */
    private val inflight = ConcurrentHashMap<String, CountDownLatch>()

    /** Decoding is memory hungry; a few at a time keep the heap flat without serializing the grid. */
    private val slots = Semaphore(min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))

    fun get(key: String): File? = synchronized(lock) {
        val file = fileFor(key)
        if (file.isFile && file.length() > 0L) {
            touch(file)
            file
        } else {
            recency.remove(file.name)
            null
        }
    }

    fun put(key: String, bitmap: Bitmap, quality: Int = 82): File {
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
            }
            val size = temporary.length()
            check(size in 1..maxBytes) { "Thumbnail exceeds cache budget" }
            synchronized(lock) {
                check(temporary.renameTo(target)) { "Unable to promote thumbnail" }
                usedBytes = if (usedBytes < 0L) measureBudget() else usedBytes + size
                touch(target)
                // Only walk the directory once the budget is actually crossed.
                if (usedBytes > maxBytes) usedBytes = evict(target)
            }
            return target
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
    ): InputStream? {
        if (!source.isFile || width <= 0 || height <= 0) return null
        val length = source.length()
        val modified = source.lastModified()
        val key = "$identity;${source.canonicalPath};$length;$modified;$width;$height;$fill;v1"

        // Opened under the index lock so a later eviction cannot pull the file from under us.
        synchronized(lock) { openIfPresent(key) }?.let { return it }
        if (!allowCreate) return null

        val gate = CountDownLatch(1)
        val owner = inflight.putIfAbsent(key, gate)
        if (owner != null) {
            owner.await()
            return synchronized(lock) { openIfPresent(key) }
        }
        try {
            // Another owner may have published between the lookup above and claiming the key.
            synchronized(lock) { openIfPresent(key) }?.let { return it }
            return generate(key, source, width, height, fill, length, modified)
        } finally {
            inflight.remove(key, gate)
            gate.countDown()
        }
    }

    private fun generate(
        key: String,
        source: File,
        width: Int,
        height: Int,
        fill: Boolean,
        length: Long,
        modified: Long,
    ): InputStream? {
        try {
            slots.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        try {
            val bitmap = try {
                createThumbnail(source, width, height, fill)
            } catch (_: OriginalRequired) {
                return null
            } ?: return null
            try {
                // An in-flight cover refresh must not publish a derivative of obsolete bytes.
                if (source.length() != length || source.lastModified() != modified) return null
                put(key, bitmap)
                return synchronized(lock) { openIfPresent(key) }
            } finally {
                bitmap.recycle()
            }
        } finally {
            slots.release()
        }
    }

    private fun openIfPresent(key: String): InputStream? {
        val file = fileFor(key)
        if (!file.isFile || file.length() <= 0L) {
            recency.remove(file.name)
            return null
        }
        touch(file)
        return file.inputStream()
    }

    private class OriginalRequired : RuntimeException()

    fun size(): Long = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    }

    fun removeAll(): Int = synchronized(lock) {
        val deleted = directory.listFiles().orEmpty().count { it.isFile && it.delete() }
        recency.clear()
        usedBytes = -1L
        deleted
    }

    private fun fileFor(key: String) = File(directory, digest(key) + ".webp")

    private fun touch(file: File) {
        recency[file.name] = ++sequence
    }

    /** Total size of the published thumbnails, seeding recency from whatever is already on disk. */
    private fun measureBudget(): Long {
        var total = 0L
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile || file.extension != "webp") continue
            total += file.length()
            if (file.name !in recency) {
                val stamp = file.lastModified()
                recency[file.name] = stamp
                if (stamp > sequence) sequence = stamp
            }
        }
        return total
    }

    private fun evict(protected: File): Long {
        var used = measureBudget()
        val candidates = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "webp" && it != protected }
            .sortedBy { recency[it.name] ?: 0L }
        for (file in candidates) {
            if (used <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                used -= size
                recency.remove(file.name)
            }
        }
        return used
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
