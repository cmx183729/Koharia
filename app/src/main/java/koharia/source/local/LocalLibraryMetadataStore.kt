package koharia.source.local

import koharia.domain.local.LocalLibraryFolderDisplayRecord
import koharia.domain.local.LocalLibraryMetadataRecord
import koharia.domain.local.LocalLibraryMetadataRepository
import koharia.domain.local.LocalLibraryMetadataSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

    fun putOverride(itemKey: String, value: LocalMetadataOverride) {
        // Every instance for this source shares the memoized snapshot, so the read-diff-write has
        // to be one step or two writers can interleave and drop each other's rows.
        synchronized(writeLock) {
            val current = memo()
            if (current.overrides[itemKey] == value) return
            memos[sourceId] = current.copy(overrides = current.overrides + (itemKey to value))
            runBlocking { repository.putOverride(sourceId, value.toRecord(itemKey)) }
            signal()
        }
    }

    fun folderDisplay(key: String): LocalFolderDisplaySettings =
        memo().folderDisplay[key] ?: LocalFolderDisplaySettings()

    fun setFolderDisplay(key: String, value: LocalFolderDisplaySettings) {
        synchronized(writeLock) {
            val current = memo()
            if (current.folderDisplay[key] == value) return
            memos[sourceId] = current.copy(folderDisplay = current.folderDisplay + (key to value))
            runBlocking { repository.putFolderDisplay(sourceId, value.toRecord(key)) }
            signal()
        }
    }

    fun bookshelfAssignments(): Map<String, String> = memo().assignments

    fun setBookshelfAssignment(itemKey: String, bookshelfId: String) {
        synchronized(writeLock) {
            val current = memo()
            if (current.assignments[itemKey] == bookshelfId) return
            memos[sourceId] = current.copy(assignments = current.assignments + (itemKey to bookshelfId))
            runBlocking { repository.putBookshelfAssignments(sourceId, mapOf(itemKey to bookshelfId)) }
            signal()
        }
    }

    fun clearBookshelfAssignment(itemKey: String) {
        synchronized(writeLock) {
            val current = memo()
            if (itemKey !in current.assignments) return
            memos[sourceId] = current.copy(assignments = current.assignments - itemKey)
            runBlocking { repository.removeBookshelfAssignments(sourceId, setOf(itemKey)) }
            signal()
        }
    }

    /**
     * Whole-map writes still arrive from the settings screen, but only the rows that differ are
     * written, and both halves land in one transaction so a reorder cannot leave an item on two
     * shelves after a crash.
     */
    fun replaceBookshelfAssignments(assignments: Map<String, String>) {
        synchronized(writeLock) {
            val current = memo()
            if (current.assignments == assignments) return
            val changed = assignments.filter { (key, value) -> current.assignments[key] != value }
            val removed = current.assignments.keys - assignments.keys
            memos[sourceId] = current.copy(assignments = assignments)
            runBlocking {
                repository.applyBookshelfAssignments(sourceId, changed, removed)
            }
            signal()
        }
    }

    fun customCoverUrls(): Set<String> = memo().covers

    fun rememberCustomCover(url: String) {
        synchronized(writeLock) {
            val current = memo()
            if (url in current.covers) return
            memos[sourceId] = current.copy(covers = current.covers + url)
            runBlocking { repository.putCustomCover(sourceId, url) }
            signal()
        }
    }

    fun metadataRevision(key: String): String? = memo().revisions[key]

    fun setMetadataRevision(key: String, revision: String) {
        synchronized(writeLock) {
            val current = memo()
            if (current.revisions[key] == revision) return
            memos[sourceId] = current.copy(revisions = current.revisions + (key to revision))
            runBlocking { repository.putMetadataRevision(sourceId, key, revision) }
            signal()
        }
    }

    fun removeItems(keys: Set<String>) {
        if (keys.isEmpty()) return
        synchronized(writeLock) {
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
    }

    fun clear() {
        synchronized(writeLock) {
            memos.remove(sourceId)
            runBlocking { repository.clear(sourceId) }
            signal()
        }
    }

    private fun memo(): Memo = memos[sourceId] ?: runBlocking { repository.load(sourceId) }.toMemo().also {
        memos[sourceId] = it
    }

    private fun signal() {
        versions.getOrPut(sourceId) { MutableStateFlow(0L) }.update { it + 1 }
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

        /**
         * Per-source invalidation counters. A shared flow drops the event once its buffer fills,
         * which leaves observers reading stale data; a state flow only conflates, so the newest
         * version is always visible to whoever collects next.
         */
        private val versions = ConcurrentHashMap<Long, MutableStateFlow<Long>>()

        /** Serialises the read-diff-write sequence across every store that shares a source. */
        private val writeLock = Any()

        fun changes(sourceId: Long): Flow<Unit> = versions.getOrPut(sourceId) { MutableStateFlow(0L) }
            .drop(1)
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
