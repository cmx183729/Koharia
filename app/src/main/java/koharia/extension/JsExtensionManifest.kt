package koharia.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI

/** Declarative metadata for a future JavaScript source extension. */
@Serializable
data class JsExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    @SerialName("script_url") val scriptUrl: String,
    @SerialName("sha256") val sha256: String,
    val capabilities: List<Capability> = emptyList(),
    val homepage: String? = null,
) {
    @Serializable
    enum class Capability {
        SEARCH,
        EXPLORE,
        CATEGORIES,
        RANKING,
        TAG_FILTER,
    }

    fun validate(): List<String> = buildList {
        if (!ID_PATTERN.matches(id)) add("id must contain only lowercase letters, digits, '.', '_' or '-'")
        if (name.trim().isEmpty()) add("name must not be blank")
        if (version.trim().isEmpty()) add("version must not be blank")
        validateHttpsUrl(scriptUrl, "script_url", ::add)
        if (!SHA256_PATTERN.matches(sha256)) add("sha256 must be 64 lowercase hexadecimal characters")
        homepage?.let { validateHttpsUrl(it, "homepage", ::add) }
        if (capabilities.toSet().size != capabilities.size) add("capabilities must not contain duplicates")
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        private fun validateHttpsUrl(value: String, field: String, errors: MutableList<String>) {
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                errors += "$field must be an https URL with a host and no user info"
            }
        }
    }
}

object JsExtensionManifestParser {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun parse(text: String): JsExtensionManifest {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "JS extension manifest exceeds size limit"
        }
        val manifest = json.decodeFromString<JsExtensionManifest>(text)
        val errors = manifest.validate()
        require(errors.isEmpty()) { "Invalid JS extension manifest: ${errors.joinToString()}" }
        return manifest
    }

    private const val MAX_MANIFEST_BYTES = 64 * 1024
}
