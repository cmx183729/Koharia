package koharia.source.local

import java.util.Locale

/** Returns whether a local image should be preferred as an explicit cover. */
internal fun isPreferredLocalCoverName(name: String): Boolean {
    val stem = name.substringBeforeLast('.', name).lowercase(Locale.ROOT)
    return stem in setOf("cover", "folder", "poster", "!cover")
}
