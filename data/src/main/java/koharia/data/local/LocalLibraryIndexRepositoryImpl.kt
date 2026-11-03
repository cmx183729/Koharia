package koharia.data.local

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.local.LocalLibraryIndexRecord
import koharia.domain.local.LocalLibraryIndexRepository
import koharia.domain.local.LocalLibraryIndexSnapshot
import koharia.domain.local.LocalLibraryIndexWrite
import tachiyomi.data.Database
import tachiyomi.data.Local_library_item

class LocalLibraryIndexRepositoryImpl(private val database: Database) : LocalLibraryIndexRepository {
    private val queries get() = database.local_libraryQueries

    override suspend fun load(sourceId: Long): LocalLibraryIndexSnapshot {
        val meta = queries.getMeta(sourceId).awaitAsOneOrNull()
        return LocalLibraryIndexSnapshot(
            scannedAt = meta?.scanned_at ?: 0L,
            schemaVersion = (meta?.schema_version ?: DEFAULT_SCHEMA_VERSION).toInt(),
            titlePurgeRegex = meta?.title_purge_regex.orEmpty(),
            folderOperation = meta?.folder_operation,
            items = queries.getItems(sourceId).awaitAsList().map(::toRecord),
            pendingRefreshItemKeys = queries.getPendingRefresh(sourceId).awaitAsList()
                .mapTo(mutableSetOf()) { it },
        )
    }

    override suspend fun apply(sourceId: Long, write: LocalLibraryIndexWrite) {
        database.transaction {
            write.removedItemKeys.chunked(MAX_IN_VARIABLES).forEach {
                queries.removeItems(sourceId, it)
            }
            write.items.forEach { put(sourceId, it) }
            write.pendingRefreshItemKeys.forEach { queries.putPendingRefresh(sourceId, it) }
            write.removedPendingRefreshItemKeys.chunked(MAX_IN_VARIABLES).forEach {
                queries.removePendingRefresh(sourceId, it)
            }
            if (write.metaChanged) {
                queries.putMeta(
                    sourceId = sourceId,
                    scannedAt = write.scannedAt,
                    schemaVersion = write.schemaVersion.toLong(),
                    titlePurgeRegex = write.titlePurgeRegex,
                    folderOperation = write.folderOperation,
                )
            }
        }
    }

    override suspend fun clear(sourceId: Long) {
        database.transaction {
            queries.clearItems(sourceId)
            queries.clearPendingRefresh(sourceId)
            queries.clearMeta(sourceId)
        }
    }

    private suspend fun put(sourceId: Long, record: LocalLibraryIndexRecord) {
        queries.putItem(
            sourceId = sourceId,
            itemKey = record.itemKey,
            rootId = record.rootId,
            relativePath = record.relativePath,
            contentType = record.contentType,
            kind = record.kind,
            format = record.format,
            sizeBytes = record.sizeBytes,
            modifiedAt = record.modifiedAt,
            fingerprint = record.fingerprint,
            locatorPath = record.locatorPath,
            documentIdentity = record.documentIdentity,
            folderIdentity = record.folderIdentity,
            missing = record.missing.toLong(),
            imageComic = record.imageComic.toLong(),
            imageComicOverride = record.imageComicOverride?.toLong(),
            virtualType = record.virtualType,
            backingPath = record.backingPath,
        )
    }

    private fun toRecord(row: Local_library_item): LocalLibraryIndexRecord = LocalLibraryIndexRecord(
        itemKey = row.item_key,
        rootId = row.root_id,
        relativePath = row.relative_path,
        contentType = row.content_type,
        kind = row.kind,
        format = row.format,
        sizeBytes = row.size_bytes,
        modifiedAt = row.modified_at,
        fingerprint = row.fingerprint,
        locatorPath = row.locator_path,
        documentIdentity = row.document_identity,
        folderIdentity = row.folder_identity,
        missing = row.missing != 0L,
        imageComic = row.image_comic != 0L,
        imageComicOverride = row.image_comic_override?.let { it != 0L },
        virtualType = row.virtual_type,
        backingPath = row.backing_path,
    )

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    companion object {
        private const val DEFAULT_SCHEMA_VERSION = 6L

        /**
         * SQLite refuses a statement with more variables than `SQLITE_MAX_VARIABLE_NUMBER`, which is
         * 999 before SQLite 3.32 - the version Android 10 and older still ship. A generated `IN ?`
         * binds one variable per element, so dropping a whole root has to be chunked.
         */
        private const val MAX_IN_VARIABLES = 900
    }
}
