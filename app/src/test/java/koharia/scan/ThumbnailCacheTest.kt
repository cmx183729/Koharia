package koharia.scan

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailCacheTest {
    @TempDir lateinit var temporary: Path

    private fun newFile(name: String = "source"): File = temporary.resolve(name).toFile().apply { createNewFile() }

    private fun newFolder(name: String = "thumbnails"): File = Files.createDirectory(temporary.resolve(name)).toFile()

    @Test
    fun `plan preserves portrait and landscape aspect ratios without upscaling`() {
        assertEquals(ThumbnailCache.Plan(512, 1024), ThumbnailCache.plan(2000, 4000, 200, 400, true))
        assertEquals(ThumbnailCache.Plan(1024, 512), ThumbnailCache.plan(4000, 2000, 400, 200, true))
        assertEquals(ThumbnailCache.Plan(200, 300), ThumbnailCache.plan(200, 300, 100, 150, true))
        assertEquals(ThumbnailCache.Plan(1024, 1), ThumbnailCache.plan(100000, 1, 100, 1, false))
    }

    @Test
    fun `large fill and invalid sizes preserve the original`() {
        assertNull(ThumbnailCache.plan(2000, 4000, 800, 1200, true))
        assertNotNull(ThumbnailCache.plan(2000, 4000, 800, 800, false))
        assertNull(ThumbnailCache.plan(0, 4000, 200, 400, true))
        assertNull(ThumbnailCache.plan(2000, 4000, -1, 400, true))
        assertNull(ThumbnailCache.plan(2000, 4000, 200, 0, true))
    }

    @Test
    fun `warm thumbnail survives store recreation without generating or changing source`() {
        val source = newFile("source").apply { writeText("original") }
        val before = source.readBytes()
        val directory = newFolder("thumbnails")
        val generated = AtomicInteger()
        val cache = ThumbnailCache(directory, 1024) { _, _, _, _ ->
            generated.incrementAndGet()
            bitmap()
        }
        assertArrayEquals(payload, cache.openOrCreate(source, "owner1", 200, 400, true, true)!!.use { it.readBytes() })
        val recreated = ThumbnailCache(directory, 1024) { _, _, _, _ -> error("Warm thumbnail decoded again") }
        assertArrayEquals(
            payload,
            recreated.openOrCreate(source, "owner1", 200, 400, true, false)!!.use {
                it.readBytes()
            },
        )
        assertEquals(1, generated.get())
        assertArrayEquals(before, source.readBytes())
        assertFalse(directory.listFiles()!!.any { it.extension == "part" })
    }

    @Test
    fun `owner version and dimensions isolate generated thumbnails`() {
        val source = newFile("source").apply { writeText("one") }
        val generated = AtomicInteger()
        val cache = ThumbnailCache(newFolder(), 1024) { _, _, _, _ ->
            generated.incrementAndGet()
            bitmap()
        }
        fun open(owner: String = "owner1", width: Int = 200, fill: Boolean = true) {
            cache.openOrCreate(source, owner, width, 400, fill, true)!!.close()
        }
        open()
        open()
        open("owner2")
        open(width = 300)
        open(fill = false)
        source.appendText("new version")
        open()
        assertEquals(5, generated.get())
    }

    @Test
    fun `empty cached thumbnail is regenerated`() {
        val source = newFile().apply { writeText("source") }
        val directory = newFolder()
        val generated = AtomicInteger()
        val cache = ThumbnailCache(directory, 1024) { _, _, _, _ ->
            generated.incrementAndGet()
            bitmap()
        }

        cache.openOrCreate(source, "owner", 200, 400, true, true)!!.close()
        directory.listFiles()!!.single().writeBytes(byteArrayOf())

        val reopened = cache.openOrCreate(source, "owner", 200, 400, true, true)

        assertNotNull(reopened)
        reopened!!.use { assertArrayEquals(payload, it.readBytes()) }
        assertEquals(2, generated.get())
    }

    @Test
    fun `same length source update invalidates thumbnail by modification time`() {
        val source = newFile().apply {
            writeText("first")
            setLastModified(1_700_000_000_000L)
        }
        val generated = AtomicInteger()
        val cache = ThumbnailCache(newFolder(), 1024) { _, _, _, _ ->
            generated.incrementAndGet()
            bitmap()
        }

        cache.openOrCreate(source, "owner", 200, 400, true, true)!!.close()
        source.writeText("other")
        source.setLastModified(1_700_000_005_000L)
        cache.openOrCreate(source, "owner", 200, 400, true, true)!!.close()

        assertEquals(2, generated.get())
    }

    @Test
    fun `disabled writes and unsupported decoding do not create cache files`() {
        val source = newFile().apply { writeText("original") }
        val directory = temporary.resolve("absent").toFile()
        val cache = ThumbnailCache(directory, 1024) { _, _, _, _ -> null }
        assertNull(cache.openOrCreate(source, "owner", 200, 400, true, false))
        assertNull(cache.openOrCreate(source, "owner", 200, 400, true, true))
        assertFalse(directory.exists())
    }

    @Test
    fun `failed compression cleans its own temporary file`() {
        val directory = newFolder()
        val cache = ThumbnailCache(directory, 1024)
        val bitmap = bitmap(success = false)
        assertTrue(runCatching { cache.put("key", bitmap) }.isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun `oversized generated thumbnail is rejected and temporary file removed`() {
        val directory = newFolder()
        val cache = ThumbnailCache(directory, 3)
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(byteArrayOf(1, 2, 3, 4))
            true
        }

        val error = runCatching { cache.put("oversized", bitmap) }.exceptionOrNull()

        assertEquals("Thumbnail exceeds cache budget", error?.message)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun `eviction stays within budget and leaves source data untouched`() {
        val directory = newFolder()
        val source = newFile().apply { writeText("original") }
        val cache = ThumbnailCache(directory, payload.size.toLong())
        cache.put("old", bitmap())
        cache.put("new", bitmap())
        assertNull(cache.get("old"))
        assertNotNull(cache.get("new"))
        assertEquals(payload.size.toLong(), cache.size())
        assertEquals(1, cache.removeAll())
        assertEquals(0L, cache.size())
        assertEquals("original", source.readText())
    }

    @Test
    fun `concurrent duplicate opens publish a single complete file`() {
        val source = newFile().apply { writeText("original") }
        val directory = newFolder()
        val generated = AtomicInteger()
        val cache = ThumbnailCache(directory, 1024) { _, _, _, _ ->
            generated.incrementAndGet()
            bitmap()
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(
                List(8) {
                    Callable { cache.openOrCreate(source, "owner", 200, 400, true, true)!!.use { it.readBytes() } }
                },
            )
            results.forEach { assertArrayEquals(payload, it.get()) }
            assertEquals(1, generated.get())
            assertEquals(1, directory.listFiles()!!.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `source changed during generation does not publish stale bytes`() {
        val source = newFile().apply { writeText("original") }
        val directory = newFolder()
        val cache = ThumbnailCache(directory, 1024) { _, _, _, _ ->
            source.appendText("changed")
            bitmap()
        }
        assertNull(cache.openOrCreate(source, "owner", 200, 400, true, true))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private fun bitmap(success: Boolean = true): Bitmap = mockk<Bitmap>(relaxed = true).also { bitmap ->
        every { bitmap.compress(any(), any(), any()) } answers {
            thirdArg<OutputStream>().write(payload)
            success
        }
    }

    companion object {
        private val payload = byteArrayOf(1, 2, 3, 4)
    }
}
