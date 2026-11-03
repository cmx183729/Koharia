package koharia.domain.local

/**
 * One stored row of the local-library index. The enums of the scanning layer are kept as their
 * names so the data module does not have to depend on the app module.
 */
data class LocalLibraryIndexRecord(
    val itemKey: String,
    val rootId: String,
    val relativePath: String,
    val contentType: String,
    val kind: String,
    val format: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val fingerprint: String?,
    val locatorPath: String,
    val documentIdentity: String?,
    val folderIdentity: String?,
    val missing: Boolean,
    val imageComic: Boolean,
    val imageComicOverride: Boolean?,
    val virtualType: String?,
    val backingPath: String?,
)

data class LocalLibraryIndexSnapshot(
    val scannedAt: Long,
    val schemaVersion: Int,
    val titlePurgeRegex: String,
    val folderOperation: String?,
    val items: List<LocalLibraryIndexRecord>,
    val pendingRefreshItemKeys: Set<String>,
)

/**
 * An incremental index write. [items] is only the set of rows that changed, so a scan that adds a
 * single chapter no longer rewrites the whole library.
 */
data class LocalLibraryIndexWrite(
    val items: List<LocalLibraryIndexRecord>,
    val removedItemKeys: Set<String>,
    val pendingRefreshItemKeys: Set<String>,
    val removedPendingRefreshItemKeys: Set<String>,
    val scannedAt: Long,
    val schemaVersion: Int,
    val titlePurgeRegex: String,
    val folderOperation: String?,
    /** False when only item or pending-refresh rows changed, so the meta row is left alone. */
    val metaChanged: Boolean = true,
)

interface LocalLibraryIndexRepository {
    suspend fun load(sourceId: Long): LocalLibraryIndexSnapshot

    /** Applies the diff in a single transaction. */
    suspend fun apply(sourceId: Long, write: LocalLibraryIndexWrite)

    suspend fun clear(sourceId: Long)
}
