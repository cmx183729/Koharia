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
