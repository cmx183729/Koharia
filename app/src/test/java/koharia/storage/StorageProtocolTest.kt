@file:Suppress("ktlint:standard:max-line-length")

package koharia.storage

import android.os.SystemClock
import dev.mihon.injekt.patchInjekt
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import koharia.scan.ScanPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.ByteArrayOutputStream

class StorageProtocolTest {
    @Test fun `paths never cross roots`() {
        for (path in listOf("../x", "a/../b", "a\\b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) {
                StoragePath.normalize(path)
            }
        }
        assertEquals("中文/a b", StoragePath.normalize("/中文/a b/"))
        assertEquals("a/b", StoragePath.child("a", "b"))
    }

    @Test fun `DAV namespace and UTF8 paths are preserved`() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/%E4%B8%AD%E6%96%87.cbz</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12</d:getcontentlength><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val entry = WebDavStorageBackend.parseProperties(
            "https://example.test/dav/".toHttpUrl(),
            xml.toByteArray(),
        ).single()
        assertEquals("中文.cbz", entry.path)
        assertEquals(12, entry.size)
        assertEquals("\"v1\"", entry.version)
        assertFalse(entry.directory)
    }

    @Test fun `DAV redirected range keeps byte bounds but does not forward credentials to the CDN`() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { cdn ->
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/signed-book")))
                cdn.enqueue(
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/10").setBody("234"),
                )
                val backend = WebDavStorageBackend(OkHttpClient(), origin.url("/dav/"), "fixture", "dummy")
                assertArrayEquals(
                    "234".toByteArray(),
                    backend.read(StorageEntry("book.cbz", false, 10, version = "\"v1\""), 2, 3),
                )
                val original = origin.takeRequest()
                val redirected = cdn.takeRequest()
                assertTrue(original.getHeader("Authorization").orEmpty().startsWith("Basic "))
                assertEquals(null, redirected.getHeader("Authorization"))
                assertEquals("bytes=2-4", redirected.getHeader("Range"))
                assertEquals("\"v1\"", redirected.getHeader("If-Match"))
                assertEquals("identity", redirected.getHeader("Accept-Encoding"))
                assertEquals(3L, cdn.responseBytes())
            }
        }
    }

    @Test fun `DAV redirected complete image is cached under its stable DAV identity`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-redirect-image").toFile()
        try {
            MockWebServer().use { origin ->
                MockWebServer().use { cdn ->
                    origin.enqueue(
                        MockResponse().setResponseCode(307).setHeader("Location", cdn.url("/temporary-signed-url")),
                    )
                    cdn.enqueue(MockResponse().setBody("image-aa"))
                    val backend = WebDavStorageBackend(OkHttpClient(), origin.url("/dav/"), "fixture", "dummy")
                    val entry = StorageEntry("page.jpg", false, 8, version = "\"v1\"")
                    repeat(2) {
                        val cache =
                            StorageBlockCache(
                                directory,
                                StorageSession(1, "fixture", "root") {
                                    true
                                },
                                backend,
                            ) { 1024 }
                        cache.openInputStream(entry, preferComplete = true).use { input ->
                            assertEquals("image-aa", input.readBytes().decodeToString())
                        }
                    }
                    assertEquals(1, origin.requestCount())
                    assertEquals(1, cdn.requestCount())
                    assertEquals(8L, cdn.responseBytes())
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV same origin redirect retains authorization and relative location`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Location", "content/page.jpg"))
            server.enqueue(MockResponse().setBody("abc"))
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/"), "fixture", "dummy")
            val output = ByteArrayOutputStream()
            backend.copyTo(StorageEntry("page.jpg", false, 3, version = "\"v1\""), output)
            assertEquals("abc", output.toString("UTF-8"))
            assertEquals(
                server.takeRequest().getHeader("Authorization"),
                server.takeRequest().getHeader("Authorization"),
            )
        }
    }

    @Test fun `DAV redirects never silently accept an ignored range`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/content"))
            server.enqueue(MockResponse().setBody("0123456789"))
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/"), "", "")
            val error = assertThrows(StorageFailure::class.java) {
                runBlocking { backend.read(StorageEntry("book.cbz", false, 10, version = "\"v1\""), 2, 3) }
            }
            assertEquals(StorageFailure.Reason.UNSUPPORTED, error.reason)
        }
    }

    @Test fun `DAV redirect loops are bounded and metadata redirects stay disabled`() = runBlocking {
        MockWebServer().use { server ->
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/"), "", "")
            repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
            val error = assertThrows(StorageFailure::class.java) {
                runBlocking { backend.read(StorageEntry("book.cbz", false, 10, version = "\"v1\""), 2, 3) }
            }
            assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
            assertEquals(6, server.requestCount())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/metadata"))
            assertThrows(StorageFailure::class.java) { runBlocking { backend.stat("") } }
            assertEquals(7, server.requestCount())
        }
    }

    @Test fun `DAV redirected archive stream fetches only the blocks read and reuses them locally`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-lazy-redirect").toFile()
        try {
            MockWebServer().use { origin ->
                MockWebServer().use { cdn ->
                    val blockSize = 256 * 1024
                    val entry = StorageEntry("archive.cbz", false, blockSize * 4L, version = "\"v1\"")
                    for (block in listOf(0, 2)) {
                        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/signed")))
                        cdn.enqueue(
                            MockResponse().setResponseCode(206)
                                .setHeader(
                                    "Content-Range",
                                    "bytes ${block * blockSize}-${(block + 1) * blockSize - 1}/${entry.size}",
                                )
                                .setBody(if (block == 0) "a".repeat(blockSize) else "c".repeat(blockSize)),
                        )
                    }
                    val backend = WebDavStorageBackend(OkHttpClient(), origin.url("/dav/"), "fixture", "dummy")
                    val cache =
                        StorageBlockCache(directory, StorageSession(1, "fixture", "root") { true }, backend) {
                            entry.size *
                                2
                        }
                    cache.openInputStream(entry).use { input ->
                        assertEquals(0, origin.requestCount())
                        assertEquals('a'.code, input.read())
                        assertEquals(1, origin.requestCount())
                        assertEquals(2L * blockSize - 1, input.skip(2L * blockSize - 1))
                        assertEquals(1, origin.requestCount())
                        assertEquals('c'.code, input.read())
                        assertEquals(2, origin.requestCount())
                    }
                    cache.openInputStream(entry).use { assertEquals('a'.code, it.read()) }
                    assertEquals(2, origin.requestCount())
                    assertEquals(2, cdn.requestCount())
                    assertEquals(2L * blockSize, cdn.responseBytes())
                    assertEquals("bytes=0-${blockSize - 1}", cdn.takeRequest().getHeader("Range"))
                    assertEquals("bytes=${2 * blockSize}-${3 * blockSize - 1}", cdn.takeRequest().getHeader("Range"))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV rejects invalid redirect destinations without leaking request slots`() = runBlocking {
        MockWebServer().use { server ->
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/"), "fixture", "dummy")
            val entry = StorageEntry("page.jpg", false, 3, version = "\"v1\"")
            val invalidLocations = listOf("ftp://example.test/file", "http://fixture:dummy@example.test/file", null)
            repeat(2) {
                for (location in invalidLocations) {
                    val response = MockResponse().setResponseCode(302)
                    if (location != null) response.setHeader("Location", location)
                    server.enqueue(response)
                    val error = assertThrows(StorageFailure::class.java) { runBlocking { backend.read(entry, 0, 3) } }
                    assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
                }
            }
            server.enqueue(MockResponse().setBody("abc"))
            assertArrayEquals("abc".toByteArray(), backend.read(entry, 0, 3))
            assertEquals(7, server.requestCount())
        }
    }

    @Test fun `DAV accepts depth one responses that omit the requested collection`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/book.cbz</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                ),
            )
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
            assertEquals(listOf("book.cbz"), backend.list("").map { it.path })
            assertEquals("1", server.takeRequest().getHeader("Depth"))
            assertEquals("0", server.takeRequest().getHeader("Depth"))
        }
    }

    @Test fun `DAV snapshot uses one depth one request and warm reads use none`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/1.jpg</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                ),
            )
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
            val session = StorageSession(1, "fixture", "root") { true }
            val routed = StorageEndpointRouter(backend, null, { null }, {})
            val snapshot = StorageSnapshot(
                SessionStorageBackend(ReadOnlyStorageBackend(routed), session),
                StorageRecordStore(session, MemoryStorageRepository(), kotlinx.serialization.json.Json),
            )
            val initial = snapshot.directory("")
            assertTrue(initial.directory.directory)
            assertEquals(listOf("1.jpg"), initial.children.map { it.path })
            assertEquals(initial, snapshot.directory(""))
            assertEquals("1", server.takeRequest().getHeader("Depth"))
            assertEquals(1, server.requestCount())
        }
    }

    @Test fun `DAV refuses entity declarations and responses outside root`() {
        val base = "https://example.test/dav/".toHttpUrl()
        val entity = """<!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///missing">]><root>&xxe;</root>"""
        assertThrows(Exception::class.java) { WebDavStorageBackend.parseProperties(base, entity.toByteArray()) }
        val escaped = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/other/book.cbz</d:href></d:response></d:multistatus>"""
        assertThrows(StorageFailure::class.java) { WebDavStorageBackend.parseProperties(base, escaped.toByteArray()) }
    }

    @Test fun `DAV range request is conditional and rejects incorrect content range`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/10").setBody("abc"),
            )
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
            val entry = StorageEntry("book.cbz", false, 10, version = "\"v1\"")
            assertArrayEquals("abc".toByteArray(), backend.read(entry, 2, 3))
            val request = server.takeRequest()
            assertEquals("bytes=2-4", request.getHeader("Range"))
            assertEquals("\"v1\"", request.getHeader("If-Match"))
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/10").setBody("abc"),
            )
            val error = assertThrows(StorageFailure::class.java) { runBlocking { backend.read(entry, 2, 3) } }
            assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        }
    }

    @Test fun `DAV range request rejects full response when server ignores range`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
            val entry = StorageEntry("book.cbz", false, 10, version = "\"v1\"")

            val error = assertThrows(StorageFailure::class.java) {
                runBlocking { backend.read(entry, 2, 3) }
            }

            assertEquals(StorageFailure.Reason.UNSUPPORTED, error.reason)
            val request = server.takeRequest()
            assertEquals("bytes=2-4", request.getHeader("Range"))
        }
    }

    @Test fun `DAV content bypasses default scan delay while metadata retains it`() = runBlocking {
        val previousInjekt = Injekt
        patchInjekt()
        mockkStatic(SystemClock::class)
        var clockCalls = 0
        every { SystemClock.elapsedRealtime() } answers { (++clockCalls * 1000).toLong() }
        val preferences = ScanPreferences(InMemoryPreferenceStore())
        Injekt.addSingleton(preferences)
        try {
            assertEquals(200, preferences.webDavRequestIntervalMs.get())
            assertEquals(3, preferences.webDavConcurrency.get())
            MockWebServer().use { server ->
                val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
                val entry = StorageEntry("1.JPG", false, 10, version = "\"v1\"")
                server.enqueue(
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/10").setBody("234"),
                )
                assertArrayEquals("234".toByteArray(), backend.read(entry, 2, 3))
                server.enqueue(MockResponse().setBody("0123456789"))
                val output = ByteArrayOutputStream()
                backend.copyTo(entry, output)
                assertEquals("0123456789", output.toString())
                assertEquals(0, clockCalls)
                val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/1.JPG</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>10</d:getcontentlength><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
                assertEquals(entry.path, backend.stat(entry.path).path)
                assertEquals(2, clockCalls)
                assertEquals(3, server.requestCount())
            }
        } finally {
            unmockkStatic(SystemClock::class)
            Injekt = previousInjekt
        }
    }

    @Test fun `DAV multi-block image cold transfer is one GET and warm reopen uses no requests`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-image-cache").toFile()
        try {
            MockWebServer().use { server ->
                val content = "x".repeat(StorageBlockCache.BLOCK_SIZE * 8 + 37)
                val version = "W/\"v1\""
                val entry = StorageEntry("1.JPG", false, content.length.toLong(), version = version)
                server.enqueue(MockResponse().setBody(content))
                server.enqueue(
                    MockResponse().setResponseCode(207).setBody(
                        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/1.JPG</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${content.length}</d:getcontentlength><d:getetag>$version</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                    ),
                )
                val session = StorageSession(1, "fixture", "root") { true }
                val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
                val cache = StorageBlockCache(directory, session, backend) { content.length.toLong() * 2 }
                repeat(2) {
                    cache.openInputStream(entry, preferComplete = true).use { input ->
                        assertEquals(content, input.readBytes().decodeToString())
                    }
                    assertEquals(2, server.requestCount())
                }
                assertEquals(null, server.takeRequest().getHeader("Range"))
                assertEquals("0", server.takeRequest().getHeader("Depth"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV concurrent image opens share one transfer and recreated cache stays warm`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-shared-image-cache").toFile()
        try {
            MockWebServer().use { server ->
                val content = "0123456789abcdef".repeat(StorageBlockCache.BLOCK_SIZE / 4 + 3)
                val entry = StorageEntry("page.jpg", false, content.length.toLong(), version = "\"v1\"")
                server.enqueue(MockResponse().setBody(content))
                val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
                val session = StorageSession(1, "fixture", "root") { true }
                val cache = StorageBlockCache(directory, session, backend) { entry.size * 2 }
                (1..8).map {
                    async(Dispatchers.IO) {
                        cache.openInputStream(entry, preferComplete = true).use { input ->
                            assertEquals(content, input.readBytes().decodeToString())
                        }
                    }
                }.awaitAll()
                assertEquals(1, server.requestCount())
                assertEquals(entry.size, server.responseBytes())
                val recreated = StorageBlockCache(directory, session, backend) { entry.size * 2 }
                repeat(3) {
                    recreated.openInputStream(entry, preferComplete = true).use { input ->
                        assertEquals(content, input.readBytes().decodeToString())
                    }
                }
                assertEquals(1, server.requestCount())
                assertEquals(entry.size, server.responseBytes())
                val request = server.takeRequest()
                assertEquals(null, request.getHeader("Range"))
                assertEquals("\"v1\"", request.getHeader("If-Match"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV connection switch rejects the old stream and isolates matching image validators`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-session-image-cache").toFile()
        try {
            MockWebServer().use { server ->
                val entry = StorageEntry("page.jpg", false, 8, version = "\"same\"")
                val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
                var firstActive = true
                val first = StorageBlockCache(
                    directory,
                    StorageSession(1, "fixture", "root") { firstActive },
                    backend,
                ) { 1024 }
                server.enqueue(MockResponse().setBody("image-aa"))
                first.openInputStream(entry, preferComplete = true).use { obsolete ->
                    assertEquals('i'.code, obsolete.read())
                    firstActive = false
                    val second = StorageBlockCache(
                        directory,
                        StorageSession(2, "fixture", "root") { true },
                        backend,
                    ) { 1024 }
                    server.enqueue(MockResponse().setBody("image-bb"))
                    repeat(2) {
                        second.openInputStream(entry, preferComplete = true).use { input ->
                            assertEquals("image-bb", input.readBytes().decodeToString())
                        }
                    }
                    assertThrows(kotlinx.coroutines.CancellationException::class.java) { obsolete.read() }
                    assertThrows(kotlinx.coroutines.CancellationException::class.java) { obsolete.skip(1) }
                    assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                        runBlocking { first.openInputStream(entry, preferComplete = true).close() }
                    }
                }
                assertEquals(2, server.requestCount())
                assertEquals(16L, server.responseBytes())
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals(null, request.getHeader("Range"))
                    assertEquals("\"same\"", request.getHeader("If-Match"))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV ignored range falls back once and warm block reads reuse the complete file`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-no-range-cache").toFile()
        try {
            MockWebServer().use { server ->
                val content = "x".repeat(StorageBlockCache.BLOCK_SIZE * 2 + 37)
                val entry = StorageEntry("book.cbz", false, content.length.toLong(), version = "\"v1\"")
                server.enqueue(MockResponse().setBody(content))
                server.enqueue(MockResponse().setBody(content))
                val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
                val cache = StorageBlockCache(directory, StorageSession(1, "fixture", "root") { true }, backend) {
                    entry.size * 2
                }
                assertArrayEquals(content.take(64).toByteArray(), cache.read(entry, 0, 64))
                assertArrayEquals(content.takeLast(37).toByteArray(), cache.read(entry, entry.size - 37, 37))
                cache.openInputStream(entry, preferComplete = true).use {
                    assertEquals(content, it.readBytes().decodeToString())
                }
                assertEquals(2, server.requestCount())
                val range = server.takeRequest()
                assertEquals("bytes=0-${StorageBlockCache.BLOCK_SIZE - 1}", range.getHeader("Range"))
                assertEquals("\"v1\"", range.getHeader("If-Match"))
                val complete = server.takeRequest()
                assertEquals(null, complete.getHeader("Range"))
                assertEquals("\"v1\"", complete.getHeader("If-Match"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV incomplete full transfer is not cached and a later retry succeeds`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dav-short-image-cache").toFile()
        try {
            MockWebServer().use { server ->
                val content = "complete image"
                val entry = StorageEntry("page.jpg", false, content.length.toLong(), version = "\"v1\"")
                server.enqueue(MockResponse().setBody("short"))
                server.enqueue(MockResponse().setBody(content))
                val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
                val cache = StorageBlockCache(directory, StorageSession(1, "fixture", "root") { true }, backend) {
                    entry.size * 2
                }
                val failure = assertThrows(StorageFailure::class.java) {
                    runBlocking { cache.openInputStream(entry, preferComplete = true).close() }
                }
                assertEquals(StorageFailure.Reason.PROTOCOL, failure.reason)
                assertFalse(directory.walkTopDown().any { it.name == "complete.bin" || it.name == "complete.part" })
                repeat(2) {
                    cache.openInputStream(entry, preferComplete = true).use { input ->
                        assertEquals(content, input.readBytes().decodeToString())
                    }
                }
                assertEquals(2, server.requestCount())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `DAV weak validators still detect changes after complete transfer`() = runBlocking {
        MockWebServer().use { server ->
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
            val entry = StorageEntry("1.jpg", false, 3, version = "W/\"old\"")
            server.enqueue(MockResponse().setBody("abc"))
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/1.jpg</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>3</d:getcontentlength><d:getetag>W/"new"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                ),
            )
            val error =
                assertThrows(StorageFailure::class.java) {
                    runBlocking { backend.copyTo(entry, ByteArrayOutputStream()) }
                }
            assertEquals(StorageFailure.Reason.CONFLICT, error.reason)
            assertEquals(2, server.requestCount())
        }
    }

    @Test fun `endpoint probe separates rejected credentials from a non-DAV root`() = runBlocking {
        MockWebServer().use { server ->
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/").toString(), "user", "secret")
            server.enqueue(MockResponse().setResponseCode(401))
            val rejected =
                assertThrows(StorageFailure::class.java) { runBlocking { backend.checkEndpointReachable() } }
            assertEquals(StorageFailure.Reason.AUTH, rejected.reason)
            server.enqueue(MockResponse().setResponseCode(404))
            backend.checkEndpointReachable()
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>index</html>"))
            backend.checkEndpointReachable()
            server.enqueue(MockResponse().setResponseCode(403))
            backend.checkEndpointReachable()
            val request = server.takeRequest()
            assertEquals("0", request.getHeader("Depth"))
            assertTrue(request.getHeader("Authorization").orEmpty().startsWith("Basic "))
        }
    }

    @Test fun `a server ignoring ranges requests explicit complete-file fallback`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("0123456789"))
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/").toString(), "", "")
            val error =
                assertThrows(StorageFailure::class.java) {
                    runBlocking { backend.read(StorageEntry("b", false, 10), 2, 3) }
                }
            assertEquals(StorageFailure.Reason.UNSUPPORTED, error.reason)
        }
    }
}

private class MockResponse {
    var code = 200
    var body = ""
    val headers = mutableMapOf<String, String>()
    fun setResponseCode(code: Int) = apply { this.code = code }
    fun setHeader(name: String, value: String) = apply { headers[name] = value }
    fun setBody(body: String) = apply { this.body = body }
}

private class MockWebServer : java.io.Closeable {
    private val responses = java.util.concurrent.LinkedBlockingQueue<MockResponse>()
    private val requests = java.util.concurrent.LinkedBlockingQueue<com.sun.net.httpserver.Headers>()
    private val count = java.util.concurrent.atomic.AtomicInteger()
    private val bytesSent = java.util.concurrent.atomic.AtomicLong()
    private val completedResponses = java.util.concurrent.LinkedBlockingQueue<Boolean>()
    private var observedResponses = 0
    private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            count.incrementAndGet()
            requests.add(exchange.requestHeaders)
            val response =
                responses.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: MockResponse().setResponseCode(500)
            response.headers.forEach { (key, value) -> exchange.responseHeaders.set(key, value) }
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.code, bytes.size.toLong())
            try {
                exchange.responseBody.use {
                    it.write(bytes)
                    bytesSent.addAndGet(bytes.size.toLong())
                }
            } finally {
                exchange.close()
                completedResponses.add(true)
            }
        }
        start()
    }
    fun requestCount(): Int = count.get()
    fun responseBytes(): Long {
        val expectedResponses = requestCount()
        while (observedResponses < expectedResponses) {
            checkNotNull(completedResponses.poll(5, java.util.concurrent.TimeUnit.SECONDS)) {
                "Timed out waiting for response byte accounting"
            }
            observedResponses++
        }
        return bytesSent.get()
    }
    fun enqueue(response: MockResponse) {
        responses.add(response)
    }
    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    fun takeRequest(): com.sun.net.httpserver.Headers = checkNotNull(
        requests.poll(5, java.util.concurrent.TimeUnit.SECONDS),
    )
    override fun close() {
        server.stop(0)
    }
}

private fun com.sun.net.httpserver.Headers.getHeader(name: String) = getFirst(name)
