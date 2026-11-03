package koharia.data.local

import app.cash.sqldelight.async.coroutines.awaitAsList
import koharia.domain.local.LocalLibraryFolderDisplayRecord
import koharia.domain.local.LocalLibraryMetadataRecord
import koharia.domain.local.LocalLibraryMetadataRepository
import koharia.domain.local.LocalLibraryMetadataSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.data.Local_library_folder_display
import tachiyomi.data.Local_library_metadata

class LocalLibraryMetadataRepositoryImpl(
    private val database: Database,
) : LocalLibraryMetadataRepository {

    private val queries get() = database.local_library_metadataQueries

    private val json = Json { ignoreUnknownKeys = true }

    private val listSerializer = ListSerializer(String.serializer())

    private val setSerializer = SetSerializer(String.serializer())

    override suspend fun load(sourceId: Long): LocalLibraryMetadataSnapshot {
        val overrides = queries.getMetadata(sourceId)
            .awaitAsList()
            .associate { it.item_key to it.toRecord() }
        val display = queries.getFolderDisplay(sourceId)
            .awaitAsList()
            .associate { it.item_key to it.toRecord() }
        val assignments = queries.getBookshelfAssignments(sourceId)
            .awaitAsList()
            .associate { it.item_key to it.bookshelf_id }
        val covers = queries.getCustomCovers(sourceId).awaitAsList().toSet()
        val revisions = queries.getMetadataRevisions(sourceId)
            .awaitAsList()
            .associate { it.item_key to it.revision }
        return LocalLibraryMetadataSnapshot(
            overrides = overrides,
            folderDisplay = display,
            bookshelfAssignments = assignments,
            customCoverUrls = covers,
            metadataRevisions = revisions,
        )
    }

    override suspend fun putOverride(sourceId: Long, record: LocalLibraryMetadataRecord) {
        queries.putMetadata(
            sourceId = sourceId,
            itemKey = record.itemKey,
            title = record.title,
            author = record.author,
            artist = record.artist,
            description = record.description,
            genres = json.encodeToString(listSerializer, record.genres),
            status = record.status?.toLong(),
            editedFields = json.encodeToString(setSerializer, record.editedFields),
            lockedFields = json.encodeToString(setSerializer, record.lockedFields),
            origin = record.origin,
            updatedAt = record.updatedAt,
        )
    }

    override suspend fun putFolderDisplay(sourceId: Long, record: LocalLibraryFolderDisplayRecord) {
        queries.putFolderDisplay(
            sourceId = sourceId,
            itemKey = record.itemKey,
            displayName = record.displayName,
            author = record.author,
            description = record.description,
            tags = json.encodeToString(listSerializer, record.tags),
            lockedFields = json.encodeToString(setSerializer, record.lockedFields),
        )
    }

    override suspend fun putBookshelfAssignments(sourceId: Long, assignments: Map<String, String>) {
        if (assignments.isEmpty()) return
        database.transaction {
            assignments.forEach { (itemKey, bookshelfId) ->
                queries.putBookshelfAssignment(sourceId, itemKey, bookshelfId)
            }
        }
    }

    override suspend fun putCustomCover(sourceId: Long, url: String) {
        queries.putCustomCover(sourceId, url)
    }

    override suspend fun putMetadataRevision(sourceId: Long, itemKey: String, revision: String) {
        queries.putMetadataRevision(sourceId, itemKey, revision)
    }

    override suspend fun removeBookshelfAssignments(sourceId: Long, itemKeys: Set<String>) {
        if (itemKeys.isEmpty()) return
        database.transaction {
            itemKeys.chunked(MAX_IN_VARIABLES).forEach {
                queries.removeBookshelfAssignments(sourceId, it)
            }
        }
    }

    override suspend fun applyBookshelfAssignments(
        sourceId: Long,
        put: Map<String, String>,
        remove: Set<String>,
    ) {
        if (put.isEmpty() && remove.isEmpty()) return
        database.transaction {
            remove.chunked(MAX_IN_VARIABLES).forEach {
                queries.removeBookshelfAssignments(sourceId, it)
            }
            put.forEach { (itemKey, bookshelfId) ->
                queries.putBookshelfAssignment(sourceId, itemKey, bookshelfId)
            }
        }
    }

    override suspend fun removeItems(sourceId: Long, itemKeys: Set<String>) {
        if (itemKeys.isEmpty()) return
        database.transaction {
            itemKeys.chunked(MAX_IN_VARIABLES).forEach { chunk ->
                queries.removeMetadata(sourceId, chunk)
                queries.removeFolderDisplay(sourceId, chunk)
                queries.removeBookshelfAssignments(sourceId, chunk)
                queries.removeMetadataRevisions(sourceId, chunk)
            }
        }
    }

    override suspend fun clear(sourceId: Long) {
        database.transaction {
            queries.clearMetadata(sourceId)
            queries.clearFolderDisplay(sourceId)
            queries.clearBookshelfAssignments(sourceId)
            queries.clearCustomCovers(sourceId)
            queries.clearMetadataRevisions(sourceId)
        }
    }

    private fun Local_library_metadata.toRecord() = LocalLibraryMetadataRecord(
        itemKey = item_key,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genres = decodeList(genres),
        status = status?.toInt(),
        editedFields = decodeSet(edited_fields),
        lockedFields = decodeSet(locked_fields),
        origin = origin,
        updatedAt = updated_at,
    )

    private fun Local_library_folder_display.toRecord() = LocalLibraryFolderDisplayRecord(
        itemKey = item_key,
        displayName = display_name,
        author = author,
        description = description,
        tags = decodeList(tags),
        lockedFields = decodeSet(locked_fields),
    )

    private fun decodeList(value: String?): List<String> =
        value?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    private fun decodeSet(value: String?): Set<String> =
        value?.let { runCatching { json.decodeFromString(setSerializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        /**
         * SQLite refuses a statement with more variables than `SQLITE_MAX_VARIABLE_NUMBER`, which is
         * 999 before SQLite 3.32 - the version Android 10 and older still ship. A generated `IN ?`
         * binds one variable per element, so a delete of a whole root has to be chunked.
         */
        const val MAX_IN_VARIABLES = 900
    }
}
