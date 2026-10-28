package koharia.source.ehentai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

internal data class ParsedTagTranslationDatabase(
    val timestamp: String,
    val entries: Map<String, String>,
)

internal object EHenTagTranslationDatabaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): ParsedTagTranslationDatabase {
        val root = json.parseToJsonElement(text).jsonObject
        val timestamp = root["head"]?.jsonObject?.get("committer")?.jsonObject
            ?.get("when")?.jsonPrimitive?.contentOrNull.orEmpty()
        val entries = buildMap {
            val namespaces = root["data"] as? JsonArray ?: JsonArray(emptyList())
            namespaces.forEach namespaceLoop@{ namespaceElement ->
                val namespaceObject = namespaceElement as? JsonObject ?: return@namespaceLoop
                val namespace = namespaceObject["namespace"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank) ?: return@namespaceLoop
                val tags = namespaceObject["data"] as? JsonObject ?: return@namespaceLoop
                tags.forEach tagLoop@{ (key, value) ->
                    val tagObject = value as? JsonObject ?: return@tagLoop
                    val rawName = tagObject["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@tagLoop
                    val translated = org.jsoup.Jsoup.parse(rawName).text().trim()
                        .takeIf(String::isNotBlank) ?: return@tagLoop
                    val translatedNamespace = namespaceDisplayName(namespace)
                    put("$namespace: $key", "$translatedNamespace: $translated")
                }
            }
        }
        check(entries.isNotEmpty()) { "Tag translation response contains no entries" }
        return ParsedTagTranslationDatabase(timestamp.ifBlank { "downloaded" }, entries)
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
}
