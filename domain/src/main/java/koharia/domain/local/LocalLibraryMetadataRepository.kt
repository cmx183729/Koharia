package koharia.domain.local

/**
 * The per-item state that used to live in one preference string per map.
 *
 * Everything here is keyed by `(sourceId, itemKey)`, so a single edit writes one row instead of
 * re-encoding a map whose size is the whole library and rewriting the preference file that also
 * holds the scan index.
 */
data class LocalLibraryMetadataRecord(
    val itemKey: String,
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Int? = null,
    val editedFields: Set<String> = emptySet(),
    val lockedFields: Set<String> = emptySet(),
    val origin: String = "user",
    val updatedAt: Long = 0L,
)

data class LocalLibraryFolderDisplayRecord(
    val itemKey: String,
    val displayName: String? = null,
    val author: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val lockedFields: Set<String> = emptySet(),
)

data class LocalLibraryMetadataSnapshot(
    val overrides: Map<String, LocalLibraryMetadataRecord> = emptyMap(),
    val folderDisplay: Map<String, LocalLibraryFolderDisplayRecord> = emptyMap(),
    val bookshelfAssignments: Map<String, String> = emptyMap(),
    val customCoverUrls: Set<String> = emptySet(),
    val metadataRevisions: Map<String, String> = emptyMap(),
)

interface LocalLibraryMetadataRepository {
    suspend fun load(sourceId: Long): LocalLibraryMetadataSnapshot

    suspend fun putOverride(sourceId: Long, record: LocalLibraryMetadataRecord)

    suspend fun putFolderDisplay(sourceId: Long, record: LocalLibraryFolderDisplayRecord)

    suspend fun putBookshelfAssignments(sourceId: Long, assignments: Map<String, String>)

    suspend fun putCustomCover(sourceId: Long, url: String)

    suspend fun putMetadataRevision(sourceId: Long, itemKey: String, revision: String)

    suspend fun removeBookshelfAssignments(sourceId: Long, itemKeys: Set<String>)

    /** Drops every row that hangs off the given items, in one transaction. */
    suspend fun removeItems(sourceId: Long, itemKeys: Set<String>)

    suspend fun clear(sourceId: Long)
}
