package koharia.source.local

import koharia.domain.local.LocalLibraryIndexRecord
import koharia.domain.local.LocalLibraryIndexRepository
import koharia.domain.local.LocalLibraryIndexSnapshot
import koharia.domain.local.LocalLibraryIndexWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable storage for [LocalLibraryIndex].
 *
 * The index used to be a single multi-megabyte JSON string inside the source's shared preferences,
 * which meant every unrelated preference write (a folder display name, a metadata revision) rewrote
 * the whole library and every index write rewrote the whole preference file. Rows now live in their
 * own table and each write is reduced to the rows that actually changed.
 *
 * [load] still returns a whole [LocalLibraryIndex] so the ~90 read sites that walk the derived maps
 * keep working; the snapshot itself is memoized per source, because the scan and the settings screens
 * hold several [LocalLibraryPreferences] instances for the same source and each of them has to see
 * the same snapshot.
 */
internal class LocalLibraryIndexStore(
    private val sourceId: Long,
    private val repository: LocalLibraryIndexRepository,
) {
    fun load(): LocalLibraryIndex = memoOrLoad().index

    /**
     * Stores [index] and leaves the pending folder-operation marker untouched. Items that are equal
     * to the memoized copy are not written, so the common case of a scan re-storing an unchanged
     * index costs nothing.
     */
    fun store(index: LocalLibraryIndex) = store(index, memoOrLoad().folderOperation)

    fun commitFolderOperation(index: LocalLibraryIndex, operation: String?) = store(index, operation)

    fun setFolderOperation(operation: String?) {
        val memo = memoOrLoad()
        store(memo.index, operation)
    }

    fun pendingFolderOperation(): String? = memoOrLoad().folderOperation

    fun removePendingChapterRefresh(itemKey: String) {
        synchronized(writeLock) {
            val memo = memoOrLoad()
            if (itemKey !in memo.index.pendingChapterRefreshItemKeys) return
            val updated = memo.index.copy(
                pendingChapterRefreshItemKeys = memo.index.pendingChapterRefreshItemKeys - itemKey,
            )
            memos[sourceId] = Memo(updated, memo.folderOperation)
            runBlocking {
                repository.apply(
                    sourceId,
                    LocalLibraryIndexWrite(
                        items = emptyList(),
                        removedItemKeys = emptySet(),
                        pendingRefreshItemKeys = emptySet(),
                        removedPendingRefreshItemKeys = setOf(itemKey),
                        scannedAt = updated.scannedAt,
                        schemaVersion = updated.schemaVersion,
                        titlePurgeRegex = updated.titlePurgeRegex,
                        folderOperation = memo.folderOperation,
                        metaChanged = false,
                    ),
                )
            }
            signal(sourceId)
        }
    }

    fun clear() {
        synchronized(writeLock) {
            memos.remove(sourceId)
            runBlocking { repository.clear(sourceId) }
            signal(sourceId)
        }
    }

    private fun store(index: LocalLibraryIndex, folderOperation: String?) {
        // Several preferences instances for one source share the memoized snapshot, so the diff and
        // the write that follows it have to be one step or two writers can interleave and drop rows.
        synchronized(writeLock) {
            val previous = memoOrLoad()
            memos[sourceId] = Memo(index, folderOperation)
            val previousItems = previous.index.itemsByKey
            val changedItems = index.items.filter { previousItems[it.itemKey] != it }
            val removedItems = previousItems.keys - index.itemsByKey.keys
            val addedPending =
                index.pendingChapterRefreshItemKeys - previous.index.pendingChapterRefreshItemKeys
            val removedPending =
                previous.index.pendingChapterRefreshItemKeys - index.pendingChapterRefreshItemKeys
            val metaChanged = previous.index.scannedAt != index.scannedAt ||
                previous.index.schemaVersion != index.schemaVersion ||
                previous.index.titlePurgeRegex != index.titlePurgeRegex ||
                previous.folderOperation != folderOperation
            if (changedItems.isEmpty() && removedItems.isEmpty() && addedPending.isEmpty() &&
                removedPending.isEmpty() && !metaChanged
            ) {
                return
            }
            runBlocking {
                repository.apply(
                    sourceId,
                    LocalLibraryIndexWrite(
                        items = changedItems.map(::toRecord),
                        removedItemKeys = removedItems,
                        pendingRefreshItemKeys = addedPending,
                        removedPendingRefreshItemKeys = removedPending,
                        scannedAt = index.scannedAt,
                        schemaVersion = index.schemaVersion,
                        titlePurgeRegex = index.titlePurgeRegex,
                        folderOperation = folderOperation,
                        metaChanged = metaChanged,
                    ),
                )
            }
            signal(sourceId)
        }
    }

    private fun memoOrLoad(): Memo {
        memos[sourceId]?.let { return it }
        val snapshot = runBlocking { repository.load(sourceId) }
        return Memo(snapshot.toIndex(), snapshot.folderOperation).also { memos[sourceId] = it }
    }

    private data class Memo(val index: LocalLibraryIndex, val folderOperation: String?)

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

        /** Emits whenever a stored index for [sourceId] changed, so observers can drop their reads. */
        fun changes(sourceId: Long): Flow<Unit> = versions.getOrPut(sourceId) { MutableStateFlow(0L) }
            .drop(1)
            .map { }

        private fun signal(sourceId: Long) {
            versions.getOrPut(sourceId) { MutableStateFlow(0L) }.update { it + 1 }
        }
    }
}

private fun LocalLibraryIndexSnapshot.toIndex(): LocalLibraryIndex = LocalLibraryIndex(
    schemaVersion = schemaVersion,
    scannedAt = scannedAt,
    items = items.map(::toItem),
    pendingChapterRefreshItemKeys = pendingRefreshItemKeys,
    titlePurgeRegex = titlePurgeRegex,
)

private fun toRecord(item: LocalLibraryItem): LocalLibraryIndexRecord = LocalLibraryIndexRecord(
    itemKey = item.itemKey,
    rootId = item.rootId,
    relativePath = item.relativePath,
    contentType = item.contentType.name,
    kind = item.kind.name,
    format = item.format,
    sizeBytes = item.sizeBytes,
    modifiedAt = item.modifiedAt,
    fingerprint = item.fingerprint,
    locatorPath = item.locatorPath,
    documentIdentity = item.documentIdentity,
    folderIdentity = item.folderIdentity,
    missing = item.missing,
    imageComic = item.imageComic,
    imageComicOverride = item.imageComicOverride,
    virtualType = item.virtualType?.name,
    backingPath = item.backingPath,
)

private fun toItem(record: LocalLibraryIndexRecord): LocalLibraryItem = LocalLibraryItem(
    itemKey = record.itemKey,
    rootId = record.rootId,
    relativePath = record.relativePath,
    contentType = LocalLibraryContentType.entries.firstOrNull { it.name == record.contentType }
        ?: LocalLibraryContentType.MIXED,
    kind = LocalLibraryItem.Kind.entries.firstOrNull { it.name == record.kind }
        ?: LocalLibraryItem.Kind.FILE_ENTRY,
    format = record.format,
    sizeBytes = record.sizeBytes,
    modifiedAt = record.modifiedAt,
    fingerprint = record.fingerprint,
    locatorPath = record.locatorPath,
    documentIdentity = record.documentIdentity,
    folderIdentity = record.folderIdentity,
    missing = record.missing,
    imageComic = record.imageComic,
    imageComicOverride = record.imageComicOverride,
    virtualType = record.virtualType?.let { name ->
        LocalLibraryItem.VirtualType.entries.firstOrNull { it.name == name }
    },
    backingPath = record.backingPath,
)
