package koharia.source.ehentai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class EHenPreferences(private val store: PreferenceStore) {
    val site: Preference<EHenSite> = store.getEnum(KEY_SITE, EHenSite.E_HENTAI)
    val cookie: Preference<String> = store.getString(Preference.privateKey(KEY_COOKIE), "")
    val translateTags: Preference<Boolean> = store.getBoolean(KEY_TRANSLATE_TAGS, true)
    val tagTranslationTimestamp: Preference<String> = store.getString(KEY_TAG_TRANSLATION_TIMESTAMP, "")

    companion object {
        private const val KEY_SITE = "ehentai_site"
        private const val KEY_COOKIE = "ehentai_cookie"
        private const val KEY_TRANSLATE_TAGS = "ehentai_translate_tags"
        private const val KEY_TAG_TRANSLATION_TIMESTAMP = "ehentai_tag_translation_timestamp"
    }
}

object EHenCookieParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val COOKIE_METADATA_KEYS = setOf("path", "domain", "expires", "expirationDate", "httpOnly", "secure", "sameSite", "storeId", "hostOnly", "session", "id")

    fun parse(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return ""
        val header = value.lineSequence().firstOrNull { it.trimStart().startsWith("Cookie:", ignoreCase = true) }
            ?.trim()?.substringAfter(':')?.trim()
            ?: value.trim().let { raw ->
                if (raw.startsWith("Cookie:", ignoreCase = true)) raw.substringAfter(':').trim() else raw
            }
        val fromJson = runCatching { extractCookieJson(json.parseToJsonElement(header)) }.getOrNull()
        val cookies = fromJson ?: header
        return cookies.split(';')
            .map(String::trim)
            .filter { it.contains('=') && it.substringBefore('=').isNotBlank() }
            .distinctBy { it.substringBefore('=').trim() }
            .joinToString("; ")
    }

    private fun extractCookieJson(element: kotlinx.serialization.json.JsonElement): String? = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val value = (obj["value"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?: return@mapNotNull null
            "$name=$value"
        }.takeIf(List<String>::isNotEmpty)?.joinToString("; ")
        is JsonObject -> {
            val cookies = element["cookies"] as? JsonArray
            if (cookies != null) {
                extractCookieJson(cookies)
            } else {
                val nameValueCookie = (element["name"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        (element["value"] as? JsonPrimitive)
                            ?.takeIf(JsonPrimitive::isString)
                            ?.contentOrNull
                            ?.let { value -> "$name=$value" }
                    }
                nameValueCookie ?: element.mapNotNull { (name, cookieValue) ->
                    (cookieValue as? JsonPrimitive)
                        ?.takeIf { it.isString && name !in COOKIE_METADATA_KEYS }
                        ?.contentOrNull
                        ?.takeIf { name.isNotBlank() }
                        ?.let { "$name=$it" }
                }.takeIf(List<String>::isNotEmpty)?.joinToString("; ")
            }
        }
        else -> null
    }
}

enum class EHenSite(val host: String, val apiHost: String) {
    E_HENTAI("e-hentai.org", "api.e-hentai.org"),
    EX_HENTAI("exhentai.org", "api.e-hentai.org"),
}
