package koharia.connection.ui

import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.source.service.SourcePreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionLibraryEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.util.UUID

@OptIn(InternalVoyagerApi::class)
class ConnectionLibrarySearchTest {
    private val holders = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        holders.forEach(ScreenModelStore::onDisposeNavigator)
        Dispatchers.resetMain()
    }

    private fun sourceManager() = object : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources: Flow<List<eu.kanade.tachiyomi.source.CatalogueSource>> = flowOf(emptyList())
        override fun get(sourceKey: Long) = null
        override fun getOrStub(sourceKey: Long) = StubSource(sourceKey, "en", "Unknown")
        override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
        override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()
        override fun getStubSources() = emptyList<StubSource>()
    }

    private val entries = listOf(
        entry(1, "Blue Moon", listOf("Drama", "Fantasy")),
        entry(2, "Red River", listOf("History")),
        entry(3, "Green Star", null),
    )

    @Test
    fun `late uncancellable refresh cannot replace newest entries`() = runBlocking {
        val requests = Channel<CompletableDeferred<List<LibraryManga>>>(Channel.UNLIMITED)
        val oldManga = Manga.create().copy(id = 1L, source = 42L, title = "Old")
        val newManga = Manga.create().copy(id = 2L, source = 42L, title = "New")
        val repository = mockk<MangaRepository>()
        coEvery { repository.getLibraryManga() } coAnswers {
            withContext(NonCancellable) {
                val request = CompletableDeferred<List<LibraryManga>>()
                requests.send(request)
                request.await()
            }
        }
        io.mockk.coEvery { repository.getMangaBySourceId(any()) } returns emptyList()
        val sourceManager = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources: Flow<List<eu.kanade.tachiyomi.source.CatalogueSource>> = flowOf(emptyList())
            override fun get(sourceKey: Long) = null
            override fun getOrStub(sourceKey: Long) = StubSource(sourceKey, "en", "Unknown")
            override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
            override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()
            override fun getStubSources() = emptyList<StubSource>()
        }
        val sourcePreferences = mockk<SourcePreferences>()
        every { sourcePreferences.sourceDisplayMode } returns preference(LibraryDisplayMode.default)
        val holder = "connection-library-refresh-${UUID.randomUUID()}".also(holders::add)
        val model = ScreenModelStore.getOrPut(holder, null) {
            ConnectionLibraryScreenModel(repository, sourceManager, sourcePreferences)
        }

        val oldRequest = withTimeout(5_000) { requests.receive() }
        model.refresh()
        val newRequest = withTimeout(5_000) { requests.receive() }
        newRequest.complete(listOf(libraryManga(newManga)))
        withTimeout(5_000) { model.state.first { it.entries.singleOrNull()?.manga == newManga && !it.isLoading } }
        oldRequest.complete(listOf(libraryManga(oldManga)))
        withTimeout(5_000) { model.state.first { !it.isLoading } }

        assertEquals(newManga, model.state.value.entries.single().manga)
    }

    @Test
    fun `refresh keeps cached entries visible while loading`() = runBlocking {
        val cached = Manga.create().copy(id = 30L, source = 42L, title = "Cached")
        val requests = Channel<CompletableDeferred<List<LibraryManga>>>(Channel.UNLIMITED)
        val repository = mockk<MangaRepository>()
        coEvery { repository.getLibraryManga() } coAnswers {
            val request = CompletableDeferred<List<LibraryManga>>()
            requests.send(request)
            request.await()
        }
        coEvery { repository.getMangaBySourceId(any()) } returns emptyList()
        val sourcePreferences = mockk<SourcePreferences>()
        every { sourcePreferences.sourceDisplayMode } returns preference(LibraryDisplayMode.default)
        val holder = "connection-library-loading-refresh-${UUID.randomUUID()}".also(holders::add)
        val model = ScreenModelStore.getOrPut(holder, null) {
            ConnectionLibraryScreenModel(
                mangaRepository = repository,
                sourceManager = sourceManager(),
                sourcePreferences,
            )
        }
        val initialRequest = withTimeout(5_000) { requests.receive() }
        initialRequest.complete(listOf(libraryManga(cached)))
        withTimeout(5_000) { model.state.first { !it.isLoading && it.entries.singleOrNull()?.manga == cached } }

        model.refresh()
        withTimeout(5_000) { requests.receive() }
        val refreshingState = model.state.value

        assertEquals(true, refreshingState.isLoading)
        assertEquals(listOf(cached), refreshingState.entries.map { it.manga })
    }

    @Test
    fun `refresh retains active genre query for new entries`() = runBlocking {
        val first = Manga.create().copy(id = 10L, source = 42L, title = "Initial", genre = listOf("Fantasy"))
        val matching = Manga.create().copy(id = 11L, source = 42L, title = "Matching", genre = listOf("Fantasy"))
        val excluded = Manga.create().copy(id = 12L, source = 42L, title = "Excluded", genre = listOf("Drama"))
        val repository = mockk<MangaRepository>()
        coEvery { repository.getLibraryManga() } returnsMany listOf(
            listOf(libraryManga(first)),
            listOf(libraryManga(matching), libraryManga(excluded)),
        )
        coEvery { repository.getMangaBySourceId(any()) } returns emptyList()
        val sourceManager = sourceManager()
        val sourcePreferences = mockk<SourcePreferences>()
        every { sourcePreferences.sourceDisplayMode } returns preference(LibraryDisplayMode.default)
        val holder = "connection-library-query-${UUID.randomUUID()}".also(holders::add)
        val model = ScreenModelStore.getOrPut(holder, null) {
            ConnectionLibraryScreenModel(repository, sourceManager, sourcePreferences)
        }
        withTimeout(5_000) { model.state.first { !it.isLoading } }

        model.searchGenre("fant")
        model.refresh()
        withTimeout(5_000) { model.state.first { !it.isLoading } }

        assertEquals("fant", model.state.value.query)
        assertEquals(listOf(matching), model.state.value.entries.map { it.manga })
    }

    @Test
    fun `failed refresh retains cached entries and exposes the error`() = runBlocking {
        val cached = Manga.create().copy(id = 20L, source = 42L, title = "Cached")
        val repository = mockk<MangaRepository>()
        coEvery { repository.getLibraryManga() } returns listOf(libraryManga(cached)) andThenThrows
            IllegalStateException("refresh failed")
        coEvery { repository.getMangaBySourceId(any()) } returns emptyList()
        val sourcePreferences = mockk<SourcePreferences>()
        every { sourcePreferences.sourceDisplayMode } returns preference(LibraryDisplayMode.default)
        val holder = "connection-library-failed-refresh-${UUID.randomUUID()}".also(holders::add)
        val model = ScreenModelStore.getOrPut(holder, null) {
            ConnectionLibraryScreenModel(
                mangaRepository = repository,
                sourceManager = sourceManager(),
                sourcePreferences,
            )
        }
        withTimeout(5_000) { model.state.first { !it.isLoading && it.entries.singleOrNull()?.manga == cached } }

        model.refresh()
        withTimeout(5_000) { model.state.first { !it.isLoading && it.error != null } }

        assertEquals(listOf(cached), model.state.value.entries.map { it.manga })
        assertEquals("refresh failed", model.state.value.error?.message)
    }

    @Test
    fun `display mode changes persist and update model state`() = runBlocking {
        val displayModeValue = MutableStateFlow<LibraryDisplayMode>(LibraryDisplayMode.default)
        val displayMode = mockk<Preference<LibraryDisplayMode>>(relaxed = true)
        every { displayMode.get() } answers { displayModeValue.value }
        every { displayMode.changes() } returns displayModeValue
        every { displayMode.set(any<LibraryDisplayMode>()) } answers {
            displayModeValue.value = firstArg<LibraryDisplayMode>()
        }
        val sourcePreferences = mockk<SourcePreferences>()
        every { sourcePreferences.sourceDisplayMode } returns displayMode
        val repository = mockk<MangaRepository> {
            coEvery { getLibraryManga() } returns emptyList()
            coEvery { getMangaBySourceId(any()) } returns emptyList()
        }
        val holder = "connection-library-display-mode-${UUID.randomUUID()}".also(holders::add)
        val model = ScreenModelStore.getOrPut(holder, null) {
            ConnectionLibraryScreenModel(repository, sourceManager(), sourcePreferences)
        }

        val changed = LibraryDisplayMode.List
        model.setDisplayMode(changed)

        io.mockk.verify { displayMode.set(changed) }
        assertEquals(changed, model.state.value.displayMode)
    }

    @Test
    fun `title search is case insensitive and trims whitespace`() {
        assertEquals(listOf(entries[0]), filterConnectionLibraryEntries(entries, "  blue ", genre = false))
    }

    @Test
    fun `genre search uses manga genres`() {
        assertEquals(listOf(entries[0]), filterConnectionLibraryEntries(entries, "fant", genre = true))
    }

    @Test
    fun `blank search returns original order`() {
        assertEquals(entries, filterConnectionLibraryEntries(entries, " ", genre = false))
    }

    private fun entry(id: Long, title: String, genre: List<String>?) =
        ConnectionLibraryEntry(Manga.create().copy(id = id, title = title, genre = genre))

    private fun libraryManga(manga: Manga) =
        LibraryManga(
            manga = manga,
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

    private fun <T> preference(value: T): Preference<T> = mockk<Preference<T>>().also {
        every { it.get() } returns value
        every { it.changes() } returns flowOf(value)
    }
}
