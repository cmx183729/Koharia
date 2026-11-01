package koharia.source.komga

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stable per-connection/account cache key; internal routing is excluded because it only changes transport. */
internal fun komgaShelfCacheNamespace(
    sourceId: Long,
    baseUrl: String,
    authMode: String,
    username: String,
    password: String,
    apiKey: String,
): String {
    val values = listOf(sourceId.toString(), baseUrl, authMode, username, password, apiKey)
    return MessageDigest.getInstance("SHA-256").run {
        if (values.none { '|' in it }) {
            update(values.joinToString("|").toByteArray(Charsets.UTF_8))
        } else {
            update("v2".toByteArray(Charsets.UTF_8))
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                update(bytes)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }
}
