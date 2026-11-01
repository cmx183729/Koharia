package koharia.source.local

/**
 * Chooses the scan fallback title from the indexed logical path, rather than a
 * transport-specific file URI or mounted display name.
 */
internal fun scanFallbackTitle(
    relativePath: String,
    isDirectory: Boolean,
    fallbackName: String = "",
): String {
    val normalizedPath = LocalLibraryLocator.normalize(relativePath)
    val segment = if (normalizedPath == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY || normalizedPath.isBlank()) {
        fallbackName
    } else {
        normalizedPath.substringAfterLast('/')
    }
    return if (isDirectory) segment else segment.substringBeforeLast('.', segment)
}

private val titleWhitespaceRun = Regex("\\s+")

/**
 * Applies the user supplied title purge pattern to a title derived from disk.
 *
 * A blank pattern, an uncompilable pattern, or a pattern that would erase the whole title all
 * fall back to the untouched value: a typo in the settings must never blank out a library.
 * Titles the user typed by hand are never passed through here.
 */
internal fun purgeLocalTitle(title: String, pattern: String): String {
    if (pattern.isBlank() || title.isBlank()) return title
    val regex = runCatching { Regex(pattern) }.getOrNull() ?: return title
    val purged = runCatching { regex.replace(title, "") }.getOrNull() ?: return title
    return purged.replace(titleWhitespaceRun, " ").trim().ifBlank { title }
}
