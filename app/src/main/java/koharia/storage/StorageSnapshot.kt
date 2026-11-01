package koharia.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class StorageDirectorySnapshot(val directory: StorageEntry, val children: List<StorageEntry>, val generation: Long)

@Serializable
private data class StorageScanCheckpoint(val completed: Set<String> = emptySet())

/** Persist complete directory generations; never interpret a failed listing as a deletion. */
class StorageSnapshot(
    private val backend: LibraryStorageBackend,
    private val store: StorageRecordStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val scanMutex = Mutex()

    private companion object {
        const val SCAN_CHECKPOINT_WRITE_INTERVAL = 16
    }

    suspend fun hasPendingScan(): Boolean = store.list("scans").isNotEmpty()
    suspend fun directory(path: String, refresh: Boolean = false): StorageDirectorySnapshot {
        val normalized = StoragePath.normalize(path)
        return mutex.withLock {
            val saved = store.get<StorageDirectorySnapshot>("directories", normalized)
            if (saved != null && !refresh) return@withLock saved
            val listing = backend.listDirectory(normalized)
            val info = listing?.directory ?: backend.stat(normalized)
            if (!info.directory || info.path != normalized) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            val entries = listing?.children ?: backend.list(normalized)
            require(entries.all { StoragePath.parent(it.path) == normalized && it.path != normalized })
            val previousGeneration = store.list("directories").maxOfOrNull { it.revision } ?: 0L
            val generation = nextStorageSnapshotGeneration(clock(), previousGeneration)
            val result = StorageDirectorySnapshot(info, entries, generation)
            store.put("directories", normalized, result, revision = generation)
            result
        }
    }

    suspend fun cachedEntry(path: String): StorageEntry? {
        val normalized = StoragePath.normalize(path)
        if (normalized.isEmpty()) return store.get<StorageDirectorySnapshot>("directories", "")?.directory
        val own = store.get<StorageDirectorySnapshot>("directories", normalized)
        val parent = store.get<StorageDirectorySnapshot>("directories", StoragePath.parent(normalized))
        return selectCachedStorageEntry(normalized, own, parent)
    }

    suspend fun scan(
        path: String,
        refresh: Boolean = false,
        forceFull: Boolean = false,
        onDirectory: suspend (StorageDirectorySnapshot) -> Unit = {
        },
    ) = scanMutex.withLock {
        val root = StoragePath.normalize(path)
        var checkpoint = if (refresh && !forceFull) store.get<StorageScanCheckpoint>("scans", root) else null
        if (refresh && checkpoint == null) {
            checkpoint = StorageScanCheckpoint()
            store.put("scans", root, checkpoint)
        }
        val pending = ArrayDeque<String>()
        pending.add(root)
        val seen = hashSetOf<String>()
        var completedSinceCheckpointWrite = 0
        try {
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val next = pending.removeFirst()
                if (!seen.add(next)) continue
                val directory = directory(next, refresh && (forceFull || next !in checkpoint?.completed.orEmpty()))
                if (refresh) {
                    checkpoint = StorageScanCheckpoint(checkpoint?.completed.orEmpty() + next)
                    completedSinceCheckpointWrite++
                    if (completedSinceCheckpointWrite >= SCAN_CHECKPOINT_WRITE_INTERVAL) {
                        store.put("scans", root, checkpoint)
                        completedSinceCheckpointWrite = 0
                    }
                }
                onDirectory(directory)
                if (next.count { it == '/' } > 128) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                directory.children.filter {
                    it.directory && it.path !in setOf(".koharia/progress", ".koharia/identities")
                }.forEach { pending.add(it.path) }
            }
            if (refresh) {
                store.list("scans").firstOrNull { it.key == root }?.let { store.remove("scans", root, it.revision) }
            }
        } catch (error: Throwable) {
            if (refresh && completedSinceCheckpointWrite > 0) {
                runCatching { store.put("scans", root, checkpoint) }
            }
            throw error
        }
    }
}

internal fun nextStorageSnapshotGeneration(now: Long, previous: Long): Long {
    require(previous < Long.MAX_VALUE) { "Storage snapshot generation exhausted" }
    return maxOf(now, previous + 1)
}

internal fun selectCachedStorageEntry(
    path: String,
    own: StorageDirectorySnapshot?,
    parent: StorageDirectorySnapshot?,
): StorageEntry? = if (path.isEmpty() || (own != null && (parent == null || own.generation > parent.generation))) {
    own?.directory
} else {
    parent?.children?.firstOrNull { it.path == path }
}
