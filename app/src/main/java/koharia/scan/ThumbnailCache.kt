package koharia.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Persistent WebP thumbnail files with a bounded, file-backed LRU policy. */
class ThumbnailCache(private val directory: File, private val maxBytes: Long) {
    init {
        check(directory.mkdirs() || directory.isDirectory)
    }

    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun put(key: String, bitmap: Bitmap, quality: Int = 82): File {
        val target = fileFor(key)
        val temporary = File(directory, "${target.name}.part")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality.coerceIn(60, 95), output))
            output.fd.sync()
        }
        check(temporary.renameTo(target)) { "Unable to promote thumbnail" }
        target.setLastModified(System.currentTimeMillis())
        evict()
        return target
    }

    fun removeAll(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }.also {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun fileFor(key: String) = File(directory, digest(key) + ".webp")
    private fun evict() {
        var used = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "webp" }.sumOf { it.length() }
        while (used > maxBytes) {
            val oldest =
                directory.listFiles().orEmpty().filter {
                    it.isFile && it.extension == "webp"
                }.minByOrNull { it.lastModified() }
                    ?: break
            used -= oldest.length()
            oldest.delete()
        }
    }

    companion object {
        fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
