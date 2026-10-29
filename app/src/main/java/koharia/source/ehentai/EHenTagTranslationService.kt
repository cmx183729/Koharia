package koharia.source.ehentai

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Downloads and caches the versioned EhTagTranslation database without coupling it to UI. */
class EHenTagTranslationService(
    private val application: Application,
    private val client: OkHttpClient,
    private val preferences: EHenPreferences,
) {
    private val mutex = Mutex()
    private val cacheFile: File
        get() = File(application.filesDir, CACHE_FILE_NAME)

    suspend fun refresh(): Result<Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                ensureActive()
                val text = downloadText()
                val parsed = parse(text)
                ensureActive()
                writeAtomically(text)
                EHenTagTranslation.replace(parsed.entries)
                preferences.tagTranslationTimestamp.set(parsed.timestamp)
                parsed.entries.size
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private suspend fun downloadText(): String {
        return try {
            download(DOWNLOAD_URL)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (primaryError: Exception) {
            ensureActive()
            try {
                download(FALLBACK_DOWNLOAD_URL)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (fallbackError: Exception) {
                throw IllegalStateException(
                    "Tag translation download failed from both sources",
                    fallbackError,
                ).also { it.addSuppressed(primaryError) }
            }
        }
    }

    private suspend fun download(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Koharia/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return client.newCall(request).await().use { response ->
            ensureActive()
            check(response.isSuccessful) { "Tag translation request failed: HTTP ${response.code}" }
            val body = response.body ?: error("Empty tag translation response")
            check(body.contentLength() <= MAX_BYTES || body.contentLength() == -1L) {
                "Tag translation response exceeds size limit"
            }
            val source = body.source()
            source.request(MAX_BYTES.toLong() + 1L)
            ensureActive()
            val bytes = source.buffer.clone().readByteArray()
            check(bytes.size <= MAX_BYTES) { "Tag translation response exceeds size limit" }
            bytes.decodeToString()
        }
    }

    fun loadCached() {
        runCatching {
            val parsed = parse(cacheFile.readText())
            EHenTagTranslation.replace(parsed.entries)
            if (preferences.tagTranslationTimestamp.get().isBlank()) {
                preferences.tagTranslationTimestamp.set(parsed.timestamp)
            }
        }
    }

    private fun writeAtomically(content: String) {
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            temporary.writeText(content)
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun parse(text: String) = EHenTagTranslationDatabaseParser.parse(text)

    companion object {
        private const val DOWNLOAD_URL = "https://fastly.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases/db.html.json"
        private const val FALLBACK_DOWNLOAD_URL = "https://raw.githubusercontent.com/EhTagTranslation/DatabaseReleases/master/db.html.json"
        private const val CACHE_FILE_NAME = "ehentai-tag-translation.json"
        private const val MAX_BYTES = 16 * 1024 * 1024
    }
}
