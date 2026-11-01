package koharia.connection

import eu.kanade.tachiyomi.source.CatalogueSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

class ConnectionLibraryAggregationTest {

    @Test
    fun `provider membership kind filter preserves unfiltered and explicit ownership`() {
        val local = source(41L, ConnectionLibraryKind.LOCAL)
        val webDav = source(42L, ConnectionLibraryKind.WEBDAV)
        val smb = source(43L, ConnectionLibraryKind.SMB)
        val server = source(44L, ConnectionLibraryKind.SERVER)
        val unknown = source(45L, ConnectionLibraryKind.UNKNOWN)
        val manager = sourceManager(local, webDav, smb, server, unknown)

        assertEquals(
            setOf(41L, 42L, 43L, 44L, 45L),
            manager.providerManagedLibrarySourceIds(),
        )
        assertEquals(
            setOf(41L),
            manager.providerManagedLibrarySourceIds(setOf(ConnectionLibraryKind.LOCAL)),
        )
        assertEquals(
            setOf(42L, 44L),
            manager.providerManagedLibrarySourceIds(
                setOf(ConnectionLibraryKind.WEBDAV, ConnectionLibraryKind.SERVER),
            ),
        )
        assertEquals(
            setOf(43L),
            manager.providerManagedLibrarySourceIds(setOf(ConnectionLibraryKind.SMB)),
        )
        assertEquals(
            setOf(45L),
            manager.providerManagedLibrarySourceIds(setOf(ConnectionLibraryKind.UNKNOWN)),
        )
    }

    @Test
    fun `favorite entries retain library aggregates`() = kotlinx.coroutines.test.runTest {
        val manga = Manga.create().copy(id = 7L, source = 41L)
        val libraryManga = LibraryManga(
            manga = manga,
            categories = listOf(4L, 8L),
            totalChapters = 12,
            readCount = 5,
            bookmarkCount = 2,
            latestUpload = 100,
            chapterFetchedAt = 90,
            lastRead = 80,
        )
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns listOf(libraryManga)
            coEvery { getMangaBySourceId(any()) } returns emptyList()
        }
        val sourceManager = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(emptyList())
            override fun get(sourceKey: Long) = null
            override fun getOrStub(sourceKey: Long) = StubSource(sourceKey, "en", "Unknown")
            override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
            override fun getCatalogueSources() = emptyList<CatalogueSource>()
            override fun getStubSources() = emptyList<StubSource>()
        }

        val entry = repository.getAggregatedConnectionLibraryEntries(sourceManager).single()

        assertEquals(libraryManga, entry.libraryManga)
        assertEquals(listOf(4L, 8L), entry.libraryManga?.categories)
        assertEquals(12L, entry.libraryManga?.totalChapters)
    }

    @Test
    fun `aggregate entries retain the owning source library kind`() = kotlinx.coroutines.test.runTest {
        val source = source(41L, ConnectionLibraryKind.WEBDAV)
        val managed = Manga.create().copy(id = 8L, source = 41L)
        val favorite = Manga.create().copy(id = 9L, source = 41L)
        val favoriteLibrary = LibraryManga(
            manga = favorite,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns listOf(favoriteLibrary)
            coEvery { getMangaBySourceId(41L) } returns listOf(managed)
        }
        coEvery { (source as ConnectionLibraryMembershipAdapter).filterLibraryEntries(listOf(managed)) } returns
            listOf(managed)

        val entries = repository.getAggregatedConnectionLibraryEntries(sourceManager(source))

        assertEquals(
            listOf(ConnectionLibraryKind.WEBDAV, ConnectionLibraryKind.WEBDAV),
            entries.map { it.libraryKind },
        )
    }

    @Test
    fun `unregistered aggregate entries keep a null library kind`() = kotlinx.coroutines.test.runTest {
        val manga = Manga.create().copy(id = 8L, source = 41L)
        val library = LibraryManga(
            manga = manga,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns listOf(library)
            coEvery { getMangaBySourceId(any()) } returns emptyList()
        }

        assertEquals(null, repository.getAggregatedConnectionLibraryEntries(sourceManager()).single().libraryKind)
    }

    @Test
    fun `favorite aggregates take precedence over duplicate managed entries`() = kotlinx.coroutines.test.runTest {
        val manga = Manga.create().copy(id = 7L, source = 41L)
        val libraryManga = LibraryManga(
            manga = manga,
            categories = listOf(4L),
            totalChapters = 8,
            readCount = 3,
            bookmarkCount = 1,
            latestUpload = 100,
            chapterFetchedAt = 90,
            lastRead = 80,
        )
        val source = source(41L)
        coEvery { (source as ConnectionLibraryMembershipAdapter).filterLibraryEntries(listOf(manga)) } returns
            listOf(manga)
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns listOf(libraryManga)
            coEvery { getMangaBySourceId(41L) } returns listOf(manga)
        }

        val entries = repository.getAggregatedConnectionLibraryEntries(sourceManager(source))

        assertEquals(1, entries.size)
        assertEquals(libraryManga, entries.single().libraryManga)
    }

    @Test
    fun `same manga id from different connections remains distinct`() {
        val first = entry(id = 7, source = 41, title = "First")
        val second = entry(id = 7, source = 42, title = "Second")

        val actual = mergeConnectionLibraryEntries(listOf(first, second))

        assertEquals(listOf(first, second), actual)
    }

    @Test
    fun `same manga id from different connections remains distinct for backup and cache cleanup`() {
        val first = Manga.create().copy(id = 7L, source = 41L)
        val second = Manga.create().copy(id = 7L, source = 42L)

        assertEquals(listOf(first, second), distinctConnectionLibraryManga(listOf(first, second)))
    }

    @Test
    fun `distinct owner-aware manga keeps first duplicate for the same owner`() {
        val first = Manga.create().copy(id = 7L, source = 41L, title = "First")
        val duplicate = Manga.create().copy(id = 7L, source = 41L, title = "Duplicate")

        assertEquals(listOf(first), distinctConnectionLibraryManga(listOf(first, duplicate)))
    }

    @Test
    fun `duplicate owner and manga id keeps first entry`() {
        val original = entry(id = 7, source = 41, title = "Saved")
        val duplicate = entry(id = 7, source = 41, title = "Managed duplicate")

        val actual = mergeConnectionLibraryEntries(listOf(original, duplicate))

        assertEquals(listOf(original), actual)
    }

    @Test
    fun `merged order follows first appearance`() {
        val first = entry(id = 1, source = 42, title = "First")
        val second = entry(id = 2, source = 41, title = "Second")
        val third = entry(id = 3, source = 42, title = "Third")

        assertEquals(listOf(first, second, third), mergeConnectionLibraryEntries(listOf(first, second, third)))
    }

    @Test
    fun `maintenance filter failure does not hide healthy entries`() = kotlinx.coroutines.test.runTest {
        val failing = source(41L)
        val healthy = source(42L)
        val failingManga = Manga.create().copy(id = 1L, source = 41L)
        val healthyManga = Manga.create().copy(id = 2L, source = 42L)
        coEvery { (failing as ConnectionLibraryMembershipAdapter).filterLibraryEntries(listOf(failingManga)) } throws
            IllegalStateException("offline")
        coEvery { (healthy as ConnectionLibraryMembershipAdapter).filterLibraryEntries(listOf(healthyManga)) } returns
            listOf(healthyManga)
        val repository = mockk<MangaRepository> {
            coEvery { getMangaBySourceId(41L) } returns listOf(failingManga)
            coEvery { getMangaBySourceId(42L) } returns listOf(healthyManga)
        }

        assertEquals(
            listOf(healthyManga),
            repository.getProviderManagedLibraryEntries(sourceManager(failing, healthy)),
        )
    }

    @Test
    fun `maintenance provider filter cannot return foreign rows`() = kotlinx.coroutines.test.runTest {
        val source = source(41L)
        val owned = Manga.create().copy(id = 1L, source = 41L)
        val foreign = Manga.create().copy(id = 2L, source = 42L)
        coEvery { (source as ConnectionLibraryMembershipAdapter).filterLibraryEntries(listOf(owned)) } returns
            listOf(owned, foreign)
        val repository = mockk<MangaRepository> {
            coEvery { getMangaBySourceId(41L) } returns listOf(owned)
        }

        assertEquals(
            listOf(owned),
            repository.getProviderManagedLibraryEntries(sourceManager(source)),
        )
    }

    @Test
    fun `failed provider filter does not hide other provider entries`() = kotlinx.coroutines.test.runTest {
        val failing = source(41L)
        val healthy = source(42L)
        val failingMembership = failing as ConnectionLibraryMembershipAdapter
        val healthyMembership = healthy as ConnectionLibraryMembershipAdapter
        val failingManga = Manga.create().copy(id = 1L, source = 41L)
        val healthyManga = Manga.create().copy(id = 2L, source = 42L)
        coEvery { failingMembership.filterLibraryEntries(listOf(failingManga)) } throws IllegalStateException("offline")
        coEvery { healthyMembership.filterLibraryEntries(listOf(healthyManga)) } returns listOf(healthyManga)
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(41L) } returns listOf(failingManga)
            coEvery { getMangaBySourceId(42L) } returns listOf(healthyManga)
        }
        val manager = sourceManager(failing, healthy)

        assertEquals(
            listOf(ConnectionLibraryEntry(healthyManga, libraryKind = ConnectionLibraryKind.UNKNOWN)),
            repository.getAggregatedConnectionLibraryEntries(manager),
        )
    }

    @Test
    fun `provider filter cannot contribute rows owned by another source`() = kotlinx.coroutines.test.runTest {
        val source = source(41L)
        val misplaced = Manga.create().copy(id = 9L, source = 99L)
        coEvery { (source as ConnectionLibraryMembershipAdapter).filterLibraryEntries(emptyList()) } returns
            listOf(misplaced)
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(41L) } returns emptyList()
        }

        assertEquals(
            emptyList<ConnectionLibraryEntry>(),
            repository.getAggregatedConnectionLibraryEntries(sourceManager(source)),
        )
    }

    @Test
    fun `provider membership adapter only receives and returns owner rows`() = kotlinx.coroutines.test.runTest {
        val source = source(41L)
        val membership = source as ConnectionLibraryMembershipAdapter
        val owned = Manga.create().copy(id = 1L, source = 41L)
        val foreign = Manga.create().copy(id = 2L, source = 42L)
        coEvery { membership.filterLibraryEntries(listOf(owned)) } returns listOf(owned, foreign)
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(41L) } returns listOf(owned, foreign)
        }

        assertEquals(
            listOf(ConnectionLibraryEntry(owned, libraryKind = ConnectionLibraryKind.UNKNOWN)),
            repository.getAggregatedConnectionLibraryEntries(sourceManager(source)),
        )
    }

    @Test
    fun `failed provider row lookup does not hide other provider entries`() = kotlinx.coroutines.test.runTest {
        val unavailable = source(41L)
        val healthy = source(42L)
        val healthyManga = Manga.create().copy(id = 2L, source = 42L)
        coEvery { (healthy as ConnectionLibraryMembershipAdapter).filterLibraryEntries(listOf(healthyManga)) } returns
            listOf(healthyManga)
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(41L) } throws IllegalStateException("database unavailable")
            coEvery { getMangaBySourceId(42L) } returns listOf(healthyManga)
        }

        assertEquals(
            listOf(ConnectionLibraryEntry(healthyManga, libraryKind = ConnectionLibraryKind.UNKNOWN)),
            repository.getAggregatedConnectionLibraryEntries(sourceManager(unavailable, healthy)),
        )
    }

    @Test
    fun `cancellation from provider row lookup remains visible`() = kotlinx.coroutines.test.runTest {
        val source = source(41L)
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(41L) } throws CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { repository.getAggregatedConnectionLibraryEntries(sourceManager(source)) }
        }
    }

    @Test
    fun `cancellation from provider filter remains visible`() = kotlinx.coroutines.test.runTest {
        val source = source(41L)
        val membership = source as ConnectionLibraryMembershipAdapter
        val manga = Manga.create().copy(id = 1L, source = 41L)
        coEvery { membership.filterLibraryEntries(listOf(manga)) } throws CancellationException("cancelled")
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(41L) } returns listOf(manga)
        }

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { repository.getAggregatedConnectionLibraryEntries(sourceManager(source)) }
        }
    }

    private fun source(
        sourceId: Long,
        libraryKind: ConnectionLibraryKind = ConnectionLibraryKind.UNKNOWN,
    ): CatalogueSource {
        val source = mockk<CatalogueSource>(
            moreInterfaces = arrayOf(
                ConnectionMangaBehaviorAdapter::class,
                ConnectionLibraryMembershipAdapter::class,
                ConnectionSource::class,
            ),
        )
        every { source.id } returns sourceId
        every { (source as ConnectionMangaBehaviorAdapter).mangaBehavior } returns
            ConnectionMangaBehavior(providerManagedLibrary = true)
        every { (source as ConnectionSource).libraryKind } returns libraryKind
        return source
    }

    private fun sourceManager(vararg sources: CatalogueSource): SourceManager = object : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(sources.toList())
        override fun get(sourceKey: Long) = sources.firstOrNull { it.id == sourceKey }
        override fun getOrStub(sourceKey: Long) = get(sourceKey) ?: StubSource(sourceKey, "en", "Unknown")
        override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
        override fun getCatalogueSources() = sources.toList()
        override fun getStubSources() = emptyList<StubSource>()
    }

    private fun entry(id: Long, source: Long, title: String) =
        ConnectionLibraryEntry(Manga.create().copy(id = id, source = source, title = title))
}
