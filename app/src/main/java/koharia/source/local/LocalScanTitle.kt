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
