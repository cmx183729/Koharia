package koharia.source.lanraragi

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun lanraragiCatalogIdentity(
    address: String,
    internalAddress: String,
    apiKey: String,
): String = MessageDigest.getInstance("SHA-256")
    .digest(
        listOf(address.trim().trimEnd('/'), internalAddress.trim().trimEnd('/'), apiKey)
            .joinToString("") { "${it.length}:$it" }
            .toByteArray(StandardCharsets.UTF_8),
    )
    .joinToString("") { "%02x".format(it) }

internal fun hasCurrentLanraragiCatalogIdentity(
    indexedIdentity: String,
    currentIdentity: String,
): Boolean = indexedIdentity.isNotBlank() && indexedIdentity == currentIdentity

internal fun lanraragiCatalogIdentityChanged(
    indexedIdentity: String,
    currentIdentity: String,
): Boolean = !hasCurrentLanraragiCatalogIdentity(indexedIdentity, currentIdentity)
