package koharia.source.ehentai

import android.app.Application
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

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
                val request = Request.Builder().url(DOWNLOAD_URL).get().build()
                client.newCall(request).await().use { response ->
                    ensureActive()
                    check(response.isSuccessful) { "Tag translation request failed: HTTP ${response.code}" }
                    val body = response.body ?: error("Empty tag translation response")
                    check(body.contentLength() <= MAX_BYTES || body.contentLength() == -1L) {
                        "Tag translation response exceeds size limit"
                    }
                    val buffered = body.source().buffer
                    buffered.request(MAX_BYTES + 1L)
                    ensureActive()
                    val bytes = buffered.clone().readByteArray()
                    check(bytes.size <= MAX_BYTES) { "Tag translation response exceeds size limit" }
                    val text = bytes.decodeToString()
                    val parsed = parse(text)
                    ensureActive()
                    writeAtomically(text)
                    EHenTagTranslation.replace(parsed.entries)
                    preferences.tagTranslationTimestamp.set(parsed.timestamp)
                    parsed.entries.size
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
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

    private fun parse(text: String): ParsedData {
        val root = json.parseToJsonElement(text).jsonObject
        val timestamp = root["head"]?.jsonObject?.get("committer")?.jsonObject
            ?.get("when")?.jsonPrimitive?.contentOrNull.orEmpty()
        val entries = buildMap {
            val namespaces = root["data"] as? JsonArray ?: JsonArray(emptyList())
            namespaces.forEach { namespaceElement ->
                val namespaceObject = namespaceElement.jsonObject
                val namespace = namespaceObject["namespace"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val tags = namespaceObject["data"]?.jsonObject ?: return@forEach
                tags.forEach { (key, value) ->
                    val rawName = value.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val translated = parseTagName(rawName)
                    if (translated != null) {
                        val translatedNamespace = namespaceDisplayName(namespace)
                        put("$namespace: $key", "$translatedNamespace: $translated")
                    }
                }
            }
        }
        check(entries.isNotEmpty()) { "Tag translation response contains no entries" }
        return ParsedData(timestamp.ifBlank { "downloaded" }, entries)
    }

    private fun parseTagName(rawName: String): String? {
        val translatedName = org.jsoup.Jsoup.parse(rawName).text().trim()
        return translatedName.takeIf(String::isNotBlank)
    }

    private fun namespaceDisplayName(namespace: String): String = when (namespace.lowercase(Locale.ROOT)) {
        "language" -> "语言"
        "artist" -> "作者"
        "character" -> "角色"
        "female" -> "女性"
        "male" -> "男性"
        "parody" -> "原作"
        "group" -> "团队"
        "mixed" -> "混合"
        "cosplayer" -> "角色扮演者"
        "reclass" -> "重新分类"
        "temp" -> "临时"
        "other" -> "其他"
        "location" -> "地点"
        else -> namespace
    }

    private data class ParsedData(val timestamp: String, val entries: Map<String, String>)

    companion object {
        private const val DOWNLOAD_URL = "https://fastly.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases/db.html.json"
        private const val CACHE_FILE_NAME = "ehentai-tag-translation.json"
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
