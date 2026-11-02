package koharia.source.local

import koharia.connection.ConnectionChapterMetadata
import koharia.domain.local.LocalLibraryIndexRecord
import koharia.domain.local.LocalLibraryIndexRepository
import koharia.domain.local.LocalLibraryIndexSnapshot
import koharia.domain.local.LocalLibraryIndexWrite
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class LocalLibraryCacheTest {
    @Test
    fun `index store writes only the rows that changed and shares one snapshot per source`() {
        val repository = FakeLocalLibraryIndexRepository()
        val store = LocalLibraryIndexStore(7001, repository)
        val item = LocalLibraryItem(
            "a",
            "root",
            "series/1.cbz",
            kind = LocalLibraryItem.Kind.CHAPTER,
            format = "cbz",
            sizeBytes = 12,
            modifiedAt = 2,
        )
        val first = LocalLibraryIndex(items = listOf(item), pendingChapterRefreshItemKeys = setOf("a"))
        store.store(first)
        assertEquals(listOf("a"), repository.writes.single().items.map { it.itemKey })
        assertEquals(setOf("a"), repository.writes.single().pendingRefreshItemKeys)
        assertEquals(item, store.load().itemsByKey["a"])

        // Re-storing an unchanged index is the common case during a scan and must not touch the table.
        store.store(first)
        assertEquals(1, repository.writes.size)

        // A second owner of the same source has to see the same snapshot, not the stale stored one.
        val other = LocalLibraryIndexStore(7001, repository)
        assertEquals(item, other.load().itemsByKey["a"])

        store.store(first.copy(items = listOf(item.copy(modifiedAt = 3))))
        assertEquals(2, repository.writes.size)
        assertEquals(3L, repository.writes.last().items.single().modifiedAt)
        assertTrue(repository.writes.last().removedItemKeys.isEmpty())

        // Dropping one pending key is a row removal, not a rewrite of the library.
        store.removePendingChapterRefresh("a")
        assertEquals(setOf("a"), repository.writes.last().removedPendingRefreshItemKeys)
        assertTrue(store.load().pendingChapterRefreshItemKeys.isEmpty())

        store.clear()
    }

    @Test
    fun `a fresh store rebuilds the index from the stored rows`() {
        val repository = FakeLocalLibraryIndexRepository(
            LocalLibraryIndexSnapshot(
                scannedAt = 5,
                schemaVersion = 6,
                titlePurgeRegex = "^\\[x]",
                folderOperation = "move",
                items = listOf(
                    LocalLibraryIndexRecord(
                        itemKey = "k1",
                        rootId = "root",
                        relativePath = "series",
                        contentType = LocalLibraryContentType.MIXED.name,
                        kind = LocalLibraryItem.Kind.SERIES.name,
                        format = "",
                        sizeBytes = 0,
                        modifiedAt = 4,
                        fingerprint = "abc",
                        locatorPath = "series",
                        documentIdentity = "content://x/series",
                        folderIdentity = "content://x/series",
                        missing = false,
                        imageComic = true,
                        imageComicOverride = null,
                        virtualType = LocalLibraryItem.VirtualType.IMAGE_SERIES.name,
                        backingPath = null,
                    ),
                ),
                pendingRefreshItemKeys = setOf("k1"),
            ),
        )
        val store = LocalLibraryIndexStore(7002, repository)
        val index = store.load()
        assertEquals(5L, index.scannedAt)
        assertEquals("^\\[x]", index.titlePurgeRegex)
        assertEquals("move", store.pendingFolderOperation())
        assertEquals(setOf("k1"), index.pendingChapterRefreshItemKeys)
        val item = index.itemsByKey.getValue("k1")
        assertEquals(LocalLibraryItem.Kind.SERIES, item.kind)
        assertEquals(LocalLibraryItem.VirtualType.IMAGE_SERIES, item.virtualType)
        assertEquals("content://x/series", item.folderIdentity)
        assertEquals(listOf("k1"), index.libraryItemsByKey.keys.toList())
        store.clear()
        assertTrue(store.load().items.isEmpty())
    }

    @Test
    fun `chapter page count survives refresh but not replacement or directory changes`() {
        val memo = localChapterMemo(null, 10, 100, "v1")
        val chapter = Chapter.create().copy(
            dateUpload = 10,
            lastPageRead = 4,
            memo = ConnectionChapterMetadata.withPagesCount(memo, 20),
        )
        assertEquals(20, ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 10, 100, "v1")))
        assertNull(ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 11, 100, "v1")))
        assertNull(ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 10, 101, "v1")))
        assertNull(ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 10, 100, "v2")))
        val legacy = chapter.copy(memo = ConnectionChapterMetadata.withPagesCount(JsonObject(emptyMap()), 20))
        assertEquals(20, ConnectionChapterMetadata.pagesCount(localChapterMemo(legacy, 10, 100, "v1")))
    }

    @Test
    fun `shelf updates retain item states but reflect membership and order changes`() {
        val cache = LocalMangaStates()
        val a = Manga.create().copy(id = 1, title = "A")
        val b = Manga.create().copy(id = 2, title = "B")
        val first = cache.update(listOf(a, b))
        val updated = cache.update(listOf(a.copy(title = "new"), b))
        assertEquals(first, updated)
        assertEquals("new", first[0].value.title)
        assertEquals(listOf(first[1], first[0]), cache.update(listOf(b, a)))
        assertEquals(listOf(first[1]), cache.update(listOf(b)))
        assertTrue(cache.update(emptyList()).isEmpty())
    }
}

/** In-memory stand-in that applies writes the way the SQL implementation does. */
private class FakeLocalLibraryIndexRepository(
    private var snapshot: LocalLibraryIndexSnapshot = EMPTY_SNAPSHOT,
) : LocalLibraryIndexRepository {
    val writes = mutableListOf<LocalLibraryIndexWrite>()

    override suspend fun load(sourceId: Long): LocalLibraryIndexSnapshot = snapshot

    override suspend fun apply(sourceId: Long, write: LocalLibraryIndexWrite) {
        writes += write
        val items = snapshot.items
            .filterNot { it.itemKey in write.removedItemKeys }
            .associateBy { it.itemKey }
            .toMutableMap()
        write.items.forEach { items[it.itemKey] = it }
        snapshot = snapshot.copy(
            scannedAt = write.scannedAt,
            schemaVersion = write.schemaVersion,
            titlePurgeRegex = write.titlePurgeRegex,
            folderOperation = write.folderOperation,
            items = items.values.toList(),
            pendingRefreshItemKeys = snapshot.pendingRefreshItemKeys -
                write.removedPendingRefreshItemKeys + write.pendingRefreshItemKeys,
        )
    }

    override suspend fun clear(sourceId: Long) {
        snapshot = EMPTY_SNAPSHOT
    }

    private companion object {
        val EMPTY_SNAPSHOT = LocalLibraryIndexSnapshot(
            scannedAt = 0,
            schemaVersion = 6,
            titlePurgeRegex = "",
            folderOperation = null,
            items = emptyList(),
            pendingRefreshItemKeys = emptySet(),
        )
    }
}
