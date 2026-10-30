package tachiyomi.domain.storage.service

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class StorageManagerDownloadDirectoryTest {
    private val context = mockk<Context>()
    private val preference = mockk<Preference<String>>()
    private val preferences = mockk<StoragePreferences> {
        every { baseStorageDirectory } returns preference
    }
    private val clock = AtomicLong(20_000)
    private val changes = Channel<String>(Channel.UNLIMITED)
    private val observing = CompletableDeferred<Unit>()
    private lateinit var root: UniFile
    private lateinit var directory: UniFile

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class, UniFile::class)
        mockkObject(DiskUtil)
        justRun { DiskUtil.createNoMediaFile(any(), any()) }
        directory = mockk()
        root = root("first", directory)
        every { preference.get() } returns "first"
        every { preference.changes() } returns flow {
            emit("first")
            observing.complete(Unit)
            for (value in changes) emit(value)
        }
    }

    @AfterEach
    fun tearDown() {
        changes.close()
        unmockkStatic(Uri::class, UniFile::class)
        unmockkObject(DiskUtil)
    }

    @Test
    fun `SAF reads share resolution but writes still check the directory`() {
        val manager = manager()
        repeat(64) { assertSame(directory, manager.getDownloadsDirectoryForRead()) }
        verify(exactly = 1) { root.createDirectory("downloads") }

        val recreated = mockk<UniFile>()
        every { root.createDirectory("downloads") } returns recreated
        assertSame(recreated, manager.getDownloadsDirectory())
        assertSame(recreated, manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `read hits do not extend the ten second lifetime`() {
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        clock.addAndGet(9_999)
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        val recreated = mockk<UniFile>()
        every { root.createDirectory("downloads") } returns recreated
        clock.incrementAndGet()
        assertSame(recreated, manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `failed resolution is retried and explicit write failure removes a stale read result`() {
        val manager = manager()
        every { root.createDirectory("downloads") } returns null
        assertNull(manager.getDownloadsDirectoryForRead())
        every { root.createDirectory("downloads") } returns directory
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        every { root.createDirectory("downloads") } returns null
        assertNull(manager.getDownloadsDirectory())
        assertNull(manager.getDownloadsDirectoryForRead())
        verify(exactly = 4) { root.createDirectory("downloads") }
    }

    @Test
    fun `native file roots retain immediate directory checks`() {
        every { root.uri.scheme } returns "file"
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        every { root.createDirectory("downloads") } returns null
        assertNull(manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `a storage change cannot reuse the previous root`() = runBlocking {
        val secondDirectory = mockk<UniFile> {
            every { exists() } returns false
        }
        root("second", secondDirectory)
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        withTimeout(5_000) { observing.await() }
        every { preference.get() } returns "second"
        changes.send("second")
        withTimeout(5_000) {
            while (manager.getDownloadsDirectoryForRead() !== secondDirectory) delay(1)
        }
        assertSame(secondDirectory, manager.getDownloadsDirectoryForRead())
    }

    @Test
    fun `consumers use the selected root before its change notification arrives`() {
        val secondDownloads = mockk<UniFile>()
        val second = root("second", secondDownloads)
        val backup = mockk<UniFile>()
        val local = mockk<UniFile>()
        every { second.createDirectory("autobackup") } returns backup
        every { second.createDirectory("local") } returns local
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())

        every { preference.get() } returns "second"
        assertSame(secondDownloads, manager.getDownloadsDirectoryForRead())
        assertSame(secondDownloads, manager.getDownloadsDirectory())
        assertSame(backup, manager.getAutomaticBackupsDirectory())
        assertSame(local, manager.getLocalSourceDirectory())
        verify(exactly = 1) { root.createDirectory("downloads") }
        verify(exactly = 0) { root.createDirectory("autobackup") }
        verify(exactly = 0) { root.createDirectory("local") }
        verify(exactly = 0) { root.delete() }
    }

    @Test
    fun `inaccessible newly selected root never falls back to the old directory`() {
        val second = root("second", mockk())
        every { second.exists() } returns false
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())

        every { preference.get() } returns "second"
        assertNull(manager.getDownloadsDirectoryForRead())
        assertNull(manager.getDownloadsDirectory())
        assertNull(manager.getAutomaticBackupsDirectory())
        assertNull(manager.getLocalSourceDirectory())
        verify(exactly = 1) { root.createDirectory("downloads") }
        verify(exactly = 0) { second.createDirectory(any()) }
    }

    @Test
    fun `temporarily unavailable selected root recovers without changing the preference`() {
        val secondDownloads = mockk<UniFile>()
        val second = root("second", secondDownloads)
        every { second.exists() } returns false
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())

        every { preference.get() } returns "second"
        assertNull(manager.getDownloadsDirectoryForRead())
        every { second.exists() } returns true
        assertSame(secondDownloads, manager.getDownloadsDirectoryForRead())
        verify(exactly = 1) { root.createDirectory("downloads") }
        verify(exactly = 1) { second.createDirectory("downloads") }
    }

    @Test
    fun `initially unavailable root recovers without restarting the manager`() {
        every { root.exists() } returns false
        val manager = manager()
        assertNull(manager.getDownloadsDirectoryForRead())

        every { root.exists() } returns true
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        verify(exactly = 1) { root.createDirectory("downloads") }
    }

    @Test
    fun `delayed obsolete notification does not restore the old storage root`() = runBlocking {
        val secondDownloads = mockk<UniFile>()
        root("second", secondDownloads)
        val manager = manager()
        withTimeout(5_000) { observing.await() }
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        every { preference.get() } returns "second"
        assertSame(secondDownloads, manager.getDownloadsDirectoryForRead())

        val changed = async(start = CoroutineStart.UNDISPATCHED) { manager.changes.first() }
        changes.send("first")
        withTimeout(5_000) { changed.await() }
        assertSame(secondDownloads, manager.getDownloadsDirectoryForRead())
        verify(exactly = 1) { root.createDirectory("downloads") }
        verify(exactly = 0) { root.delete() }
    }

    @Test
    fun `concurrent cold reads resolve SAF once`() {
        val manager = manager()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { root.createDirectory("downloads") } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            directory
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val first = executor.submit<UniFile?> { manager.getDownloadsDirectoryForRead() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val rest = List(7) { executor.submit<UniFile?> { manager.getDownloadsDirectoryForRead() } }
            release.countDown()
            (rest + first).forEach { assertSame(directory, it.get(5, TimeUnit.SECONDS)) }
            verify(exactly = 1) { root.createDirectory("downloads") }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `explicit invalidation forces fresh resolution`() {
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectoryForRead())
        manager.invalidateDownloadsDirectoryCache()
        val recreated = mockk<UniFile>()
        every { root.createDirectory("downloads") } returns recreated
        assertSame(recreated, manager.getDownloadsDirectoryForRead())
        verify(exactly = 2) { root.createDirectory("downloads") }
    }

    @Test
    fun `selecting a global storage root does not eagerly create unused directories`() = runBlocking {
        val second = root("second", mockk())
        val manager = manager()
        withTimeout(5_000) { observing.await() }
        val changed = async(start = CoroutineStart.UNDISPATCHED) { manager.changes.first() }
        every { preference.get() } returns "second"
        changes.send("second")
        withTimeout(5_000) { changed.await() }

        verify(exactly = 0) { root.createDirectory(any()) }
        verify(exactly = 0) { second.createDirectory(any()) }
        verify(exactly = 0) { root.delete() }
        verify(exactly = 0) { second.delete() }
    }

    @Test
    fun `backup and local directories are created only by their explicit consumers`() {
        val backup = mockk<UniFile>()
        val local = mockk<UniFile>()
        every { root.createDirectory("autobackup") } returns backup
        every { root.createDirectory("local") } returns local
        val manager = manager()

        assertSame(backup, manager.getAutomaticBackupsDirectory())
        assertSame(local, manager.getLocalSourceDirectory())
        verify(exactly = 1) { root.createDirectory("autobackup") }
        verify(exactly = 1) { root.createDirectory("local") }
        verify(exactly = 0) { root.createDirectory("downloads") }
    }

    @Test
    fun `custom cover lookup does not create a directory`() {
        val manager = manager()

        every { root.findFile("covers") } returns null

        assertNull(manager.getCustomCoversDirectory())
        verify(exactly = 1) { root.findFile("covers") }
        verify(exactly = 0) { root.createDirectory("covers") }
        verify(exactly = 0) { DiskUtil.createNoMediaFile(any(), any()) }
    }

    @Test
    fun `custom cover lookup uses the newly selected root before notification`() {
        val secondCovers = mockk<UniFile> {
            every { isDirectory } returns true
        }
        val second = root("second", mockk())
        every { second.findFile("covers") } returns secondCovers
        val manager = manager()

        every { preference.get() } returns "second"

        assertSame(secondCovers, manager.getCustomCoversDirectory())
        verify(exactly = 0) { root.findFile("covers") }
        verify(exactly = 0) { second.createDirectory("covers") }
    }

    @Test
    fun `custom cover writes create covers under the selected root`() {
        val covers = mockk<UniFile> {
            every { isDirectory } returns true
        }
        val manager = manager()

        every { root.findFile("covers") } returns null
        every { root.createDirectory("covers") } returns covers

        assertSame(covers, manager.getCustomCoversDirectory(create = true))
        verify(exactly = 1) { root.findFile("covers") }
        verify(exactly = 1) { root.createDirectory("covers") }
        verify(exactly = 1) { DiskUtil.createNoMediaFile(covers, context) }
    }

    @Test
    fun `existing custom covers are reused without creating a replacement`() {
        val covers = mockk<UniFile> {
            every { isDirectory } returns true
        }
        val manager = manager()

        every { root.findFile("covers") } returns covers

        assertSame(covers, manager.getCustomCoversDirectory(create = true))
        verify(exactly = 1) { root.findFile("covers") }
        verify(exactly = 0) { root.createDirectory("covers") }
        verify(exactly = 1) { DiskUtil.createNoMediaFile(covers, context) }
    }

    @Test
    fun `non-directory custom cover entries are not returned`() {
        val file = mockk<UniFile> {
            every { isDirectory } returns false
        }
        val manager = manager()

        every { root.findFile("covers") } returns file

        assertNull(manager.getCustomCoversDirectory())
        verify(exactly = 1) { root.findFile("covers") }
        verify(exactly = 0) { root.createDirectory("covers") }
    }

    @Test
    fun `custom cover creation preserves an existing non-directory entry`() {
        val file = mockk<UniFile> {
            every { isDirectory } returns false
        }
        val manager = manager()

        every { root.findFile("covers") } returns file

        assertNull(manager.getCustomCoversDirectory(create = true))
        verify(exactly = 1) { root.findFile("covers") }
        verify(exactly = 0) { root.createDirectory("covers") }
        verify(exactly = 0) { file.delete() }
        verify(exactly = 0) { DiskUtil.createNoMediaFile(any(), any()) }
    }

    @Test
    fun `creating downloads still adds media scanner protection`() {
        val manager = manager()
        assertSame(directory, manager.getDownloadsDirectory())
        verify(exactly = 1) { DiskUtil.createNoMediaFile(directory, context) }
    }

    private fun manager() = StorageManager(context, preferences, clock::get)

    private fun root(name: String, downloads: UniFile): UniFile {
        val uri = mockk<Uri> { every { scheme } returns "content" }
        every { Uri.parse(name) } returns uri
        val root = mockk<UniFile> {
            every { this@mockk.uri } returns uri
            every { exists() } returns true
            every { createDirectory(any()) } returns null
            every { createDirectory("downloads") } returns downloads
        }
        every { UniFile.fromUri(context, uri) } returns root
        return root
    }
}
