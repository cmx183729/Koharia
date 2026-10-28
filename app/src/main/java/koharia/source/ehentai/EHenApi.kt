package koharia.source.ehentai

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class EHenApi(
    networkClient: OkHttpClient,
    private val preferences: EHenPreferences,
) {
    private val client = networkClient.newBuilder().apply {
        interceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
        networkInterceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor }
        cookieJar(okhttp3.CookieJar.NO_COOKIES)
    }.callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun metadata(gid: Long, token: String): EHenGalleryMetadata = withContext(Dispatchers.IO) {
        require(gid > 0 && token.matches(Regex("[0-9a-fA-F]{10,}"))) { "Invalid gallery identity" }
        val body = buildJsonObject {
            put("method", "gdata")
            put("gidlist", buildJsonArray {
                add(buildJsonObject { put("gid", gid); put("token", token) })
            })
            put("namespace", 1)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://${preferences.site.get().apiHost}/api.php")
            .header("Accept", "application/json")
            .apply { preferences.cookie.get().trim().takeIf(String::isNotEmpty)?.let { header("Cookie", it) } }
            .post(body)
            .build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "E-Hentai metadata request failed: HTTP ${response.code}" }
            val responseBody = response.body ?: error("Empty E-Hentai metadata response")
            check(responseBody.contentLength() <= MAX_RESPONSE_BYTES || responseBody.contentLength() == -1L) {
                "E-Hentai metadata response exceeds size limit"
            }
            val text = responseBody.source().apply { request(MAX_RESPONSE_BYTES.toLong() + 1) }.buffer.clone().readUtf8()
            check(text.length <= MAX_RESPONSE_BYTES) { "E-Hentai metadata response exceeds size limit" }
            EHenMetadataParser.parse(text, gid, token)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
