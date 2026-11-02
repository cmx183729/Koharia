package koharia.source.local

import koharia.domain.local.LocalLibraryFolderDisplayRecord
import koharia.domain.local.LocalLibraryMetadataRecord
import koharia.domain.local.LocalLibraryMetadataRepository
import koharia.domain.local.LocalLibraryMetadataSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Row-level access to the per-item state that used to sit in preference strings.
 *
 * Every mutator writes only the affected rows. The old code re-encoded the whole overrides map and
 * rewrote the whole preference file that also holds the scan index, so editing one title cost
 * O(library) work twice over.
 */
internal class LocalLibraryMetadataStore(
    private val sourceId: Long,
    private val repository: LocalLibraryMetadataRepository,
) {
    fun overrides(): Map<String, LocalMetadataOverride> = memo().overrides

    @Synchronized
    fun putOverride(itemKey: String, value: LocalMetadataOverride) {
        val current = memo()
        if (current.overrides[itemKey] == value) return
        memos[sourceId] = current.copy(overrides = current.overrides + (itemKey to value))
        runBlocking { repository.putOverride(sourceId, value.toRecord(itemKey)) }
        signal()
    }

    fun folderDisplay(key: String): LocalFolderDisplaySettings =
        memo().folderDisplay[key] ?: LocalFolderDisplaySettings()

    @Synchronized
    fun setFolderDisplay(key: String, value: LocalFolderDisplaySettings) {
        val current = memo()
        if (current.folderDisplay[key] == value) return
        memos[sourceId] = current.copy(folderDisplay = current.folderDisplay + (key to value))
        runBlocking { repository.putFolderDisplay(sourceId, value.toRecord(key)) }
        signal()
    }

    fun bookshelfAssignments(): Map<String, String> = memo().assignments

    @Synchronized
    fun setBookshelfAssignment(itemKey: String, bookshelfId: String) {
        val current = memo()
        if (current.assignments[itemKey] == bookshelfId) return
        memos[sourceId] = current.copy(assignments = current.assignments + (itemKey to bookshelfId))
        runBlocking { repository.putBookshelfAssignments(sourceId, mapOf(itemKey to bookshelfId)) }
        signal()
    }

    @Synchronized
    fun clearBookshelfAssignment(itemKey: String) {
        val current = memo()
        if (itemKey !in current.assignments) return
        memos[sourceId] = current.copy(assignments = current.assignments - itemKey)
        runBlocking { repository.removeBookshelfAssignments(sourceId, setOf(itemKey)) }
        signal()
    }

    /**
     * Whole-map writes still arrive from the settings screen, but only the rows that differ are
     * written, so saving a draft no longer rewrites every assignment of the library.
     */
    @Synchronized
    fun replaceBookshelfAssignments(assignments: Map<String, String>) {
        val current = memo()
        if (current.assignments == assignments) return
        val changed = assignments.filter { (key, value) -> current.assignments[key] != value }
        val removed = current.assignments.keys - assignments.keys
        memos[sourceId] = current.copy(assignments = assignments)
        runBlocking {
            repository.putBookshelfAssignments(sourceId, changed)
            repository.removeBookshelfAssignments(sourceId, removed)
        }
        signal()
    }

    fun customCoverUrls(): Set<String> = memo().covers

    @Synchronized
    fun rememberCustomCover(url: String) {
        val current = memo()
        if (url in current.covers) return
        memos[sourceId] = current.copy(covers = current.covers + url)
        runBlocking { repository.putCustomCover(sourceId, url) }
        signal()
    }

    fun metadataRevision(key: String): String? = memo().revisions[key]

    @Synchronized
    fun setMetadataRevision(key: String, revision: String) {
        val current = memo()
        if (current.revisions[key] == revision) return
        memos[sourceId] = current.copy(revisions = current.revisions + (key to revision))
        runBlocking { repository.putMetadataRevision(sourceId, key, revision) }
        signal()
    }

    @Synchronized
    fun removeItems(keys: Set<String>) {
        if (keys.isEmpty()) return
        val current = memo()
        memos[sourceId] = current.copy(
            overrides = current.overrides - keys,
            folderDisplay = current.folderDisplay - keys,
            assignments = current.assignments - keys,
            revisions = current.revisions - keys,
        )
        runBlocking { repository.removeItems(sourceId, keys) }
        signal()
    }

    @Synchronized
    fun clear() {
        memos.remove(sourceId)
        runBlocking { repository.clear(sourceId) }
        signal()
    }

    private fun memo(): Memo = memos[sourceId] ?: runBlocking { repository.load(sourceId) }.toMemo().also {
        memos[sourceId] = it
    }

    private fun signal() {
        writes.tryEmit(sourceId)
    }

    private data class Memo(
        val overrides: Map<String, LocalMetadataOverride>,
        val folderDisplay: Map<String, LocalFolderDisplaySettings>,
        val assignments: Map<String, String>,
        val covers: Set<String>,
        val revisions: Map<String, String>,
    )

    private fun LocalLibraryMetadataSnapshot.toMemo() = Memo(
        overrides = overrides.mapValues { it.value.toOverride() },
        folderDisplay = folderDisplay.mapValues { it.value.toSettings() },
        assignments = bookshelfAssignments,
        covers = customCoverUrls,
        revisions = metadataRevisions,
    )

    companion object {
        private val memos = ConcurrentHashMap<Long, Memo>()
        private val writes = MutableSharedFlow<Long>(extraBufferCapacity = 64)

        fun changes(sourceId: Long): Flow<Unit> = writes.asSharedFlow()
            .filter { it == sourceId }
            .map { }
    }
}

private fun LocalMetadataOverride.toRecord(itemKey: String) = LocalLibraryMetadataRecord(
    itemKey = itemKey,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = genres,
    status = status,
    editedFields = editedFields,
    lockedFields = lockedFields,
    origin = source,
    updatedAt = updatedAt,
)

private fun LocalLibraryMetadataRecord.toOverride() = LocalMetadataOverride(
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = genres,
    status = status,
    editedFields = editedFields,
    lockedFields = lockedFields,
    source = origin,
    updatedAt = updatedAt,
)

private fun LocalFolderDisplaySettings.toRecord(itemKey: String) = LocalLibraryFolderDisplayRecord(
    itemKey = itemKey,
    displayName = displayName,
    author = author,
    description = description,
    tags = tags,
    lockedFields = lockedFields,
)

private fun LocalLibraryFolderDisplayRecord.toSettings() = LocalFolderDisplaySettings(
    displayName = displayName,
    author = author,
    description = description,
    tags = tags,
    lockedFields = lockedFields,
)
