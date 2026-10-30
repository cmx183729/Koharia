package eu.kanade.tachiyomi.data.coil

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path

class MangaCoverThumbnailTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `file cover returns generated thumbnail with owner key and request bounds`() = runBlocking {
        val source = source()
        var closed = false
        val fetcher = fetcher(source, options()) { file, identity, width, height, fill, allowCreate ->
            assertEquals(source, file)
            assertEquals("connection42;cover7;version3", identity)
            assertEquals(200, width)
            assertEquals(400, height)
            assertTrue(fill)
            assertTrue(allowCreate)
            object : ByteArrayInputStream(byteArrayOf(4, 5, 6)) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        val result = fetcher.fetch() as SourceFetchResult
        assertEquals("image/webp", result.mimeType)
        assertEquals(DataSource.DISK, result.dataSource)
        result.source.use { assertArrayEquals(byteArrayOf(4, 5, 6), it.source().readByteArray()) }
        assertTrue(closed)
        assertEquals("original", source.readText())
    }

    @Test
    fun `original size bypasses thumbnail for cover zoom save and share`() = runBlocking {
        val result = fetcher(source(), options().copy(size = Size.ORIGINAL)) { _, _, _, _, _, _ ->
            error("Original request must not load a derivative")
        }.fetch() as SourceFetchResult
        result.source.use { assertEquals("original", it.source().readUtf8()) }
    }

    @Test
    fun `disabled disk reads bypass thumbnails`() = runBlocking {
        for (policy in listOf(CachePolicy.DISABLED, CachePolicy.WRITE_ONLY)) {
            val result = fetcher(source(), options().copy(diskCachePolicy = policy)) { _, _, _, _, _, _ ->
                error("Disk reads are disabled")
            }.fetch() as SourceFetchResult
            result.source.use { assertEquals("original", it.source().readUtf8()) }
        }
    }

    @Test
    fun `read only cache permits existing derivative but not generation`() = runBlocking {
        var called = false
        val result = fetcher(source(), options().copy(diskCachePolicy = CachePolicy.READ_ONLY)) {
                _,
                _,
                _,
                _,
                _,
                create,
            ->
            assertFalse(create)
            called = true
            ByteArrayInputStream(byteArrayOf(7))
        }.fetch() as SourceFetchResult
        result.source.use { assertArrayEquals(byteArrayOf(7), it.source().readByteArray()) }
        assertTrue(called)
    }

    @Test
    fun `unsupported format or large request falls back to original`() = runBlocking {
        val result = fetcher(source(), options()) { _, _, _, _, _, _ -> null }.fetch() as SourceFetchResult
        result.source.use { assertEquals("original", it.source().readUtf8()) }
    }

    @Test
    fun `custom cover stream stays original and is never recompressed`() = runBlocking {
        val fetcher = fetcher(
            source(),
            options(),
            custom = { ByteArrayInputStream("custom".toByteArray()) },
        ) { _, _, _, _, _, _ -> error("Custom original must remain untouched") }
        val result = fetcher.fetch() as SourceFetchResult
        result.source.use { assertEquals("custom", it.source().readUtf8()) }
    }

    @Test
    fun `HTTP snapshot thumbnail closes lease without network`() = runBlocking {
        val source = source()
        val snapshot = mockk<DiskCache.Snapshot>(relaxed = true)
        every { snapshot.data } returns source.toOkioPath()
        val cache = mockk<DiskCache>()
        every { cache.fileSystem } returns FileSystem.SYSTEM
        every { cache.openSnapshot(any()) } returns snapshot
        val loader = mockk<ImageLoader>()
        every { loader.diskCache } returns cache
        val result = fetcher(
            source,
            options(),
            url = "https://example.test/cover",
            imageLoader = loader,
        ) { _, _, _, _, _, _ -> ByteArrayInputStream(byteArrayOf(9)) }.fetch() as SourceFetchResult
        result.source.use { assertArrayEquals(byteArrayOf(9), it.source().readByteArray()) }
        verify(exactly = 1) { snapshot.close() }
    }

    @Test
    fun `network snapshot thumbnail preserves network source and closes lease`() = runBlocking {
        val source = source()
        val snapshot = mockk<DiskCache.Snapshot>(relaxed = true)
        every { snapshot.data } returns source.toOkioPath()
        val cache = mockk<DiskCache>()
        every { cache.fileSystem } returns FileSystem.SYSTEM
        val loader = mockk<ImageLoader>()
        every { loader.diskCache } returns cache
        val mangaFetcher = fetcher(source, options(), imageLoader = loader) { _, _, _, _, _, _ ->
            ByteArrayInputStream(byteArrayOf(8))
        }
        val result = mangaFetcher.snapshotLoader(snapshot, DataSource.NETWORK) as SourceFetchResult
        assertEquals(DataSource.NETWORK, result.dataSource)
        result.source.close()
        verify(exactly = 1) { snapshot.close() }
    }

    @Test
    fun `snapshot original fallback retains lease until result closes`() = runBlocking {
        val source = source()
        val snapshot = mockk<DiskCache.Snapshot>(relaxed = true)
        every { snapshot.data } returns source.toOkioPath()
        val cache = mockk<DiskCache>()
        every { cache.fileSystem } returns FileSystem.SYSTEM
        every { cache.openSnapshot(any()) } returns snapshot
        val loader = mockk<ImageLoader>()
        every { loader.diskCache } returns cache
        val result = fetcher(
            source,
            options(),
            url = "https://example.test/cover",
            imageLoader = loader,
        ) { _, _, _, _, _, _ -> null }.fetch() as SourceFetchResult
        verify(exactly = 0) { snapshot.close() }
        result.source.use { assertEquals("original", it.source().readUtf8()) }
        verify(exactly = 1) { snapshot.close() }
    }

    private fun options() = Options(mockk<Context>(), size = Size(200, 400), scale = Scale.FILL)

    private fun source(): File = temporary.resolve("source").toFile().apply { writeText("original") }

    private fun fetcher(
        file: File,
        options: Options,
        custom: suspend () -> InputStream? = { null },
        url: String = "file://${file.path}",
        imageLoader: ImageLoader = mockk(),
        thumbnail: (File, String, Int, Int, Boolean, Boolean) -> InputStream?,
    ) = MangaCoverFetcher(
        url = url,
        isLibraryManga = false,
        options = options,
        coverFileLazy = lazy { null },
        openCustomCover = custom,
        diskCacheKeyLazy = lazy { "connection42;cover7;version3" },
        sourceLazy = lazy { null },
        chapterThumbnailSourceLazy = lazy { null },
        callFactoryLazy = lazy { error("Local thumbnail must not fetch remote data") },
        imageLoader = imageLoader,
        openThumbnail = thumbnail,
    )
}
