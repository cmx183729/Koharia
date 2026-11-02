package koharia.core.archive

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val lock = Any()
    private val cursorLock = Any()
    private val descriptor = ParcelFileDescriptor.dup(pfd.fileDescriptor)
    private val streams = mutableSetOf<ArchiveInputStream>()
    private var closed = false

    /**
     * A libarchive session kept alive between entries so that reading a chapter front to back walks
     * each header once. Every fresh session has to replay the headers from the first entry, which
     * makes opening page *n* cost *n* header reads; across a chapter that is quadratic, and it is
     * the reason the last pages of a long archive open slowly.
     *
     * The session is only reused for an entry *after* the one it last handed out, and only when
     * nothing else is holding it; libarchive skips whatever was left unread of the previous entry
     * through the seek callback. Anything else - a backwards jump, the same entry twice, a page
     * still being decoded - falls back to a fresh session, which is exactly the previous behaviour.
     */
    private var cursor: ArchiveInputStream? = null

    /** Index of the entry [cursor] is currently positioned at, or -1 when there is no cursor. */
    private var cursorIndex = -1
    private var cursorBusy = false

    private var entryIndex: Map<String, Int>? = null
    private val entryIndexLock = Any()

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = openStream().use {
        block(generateSequence { it.getNextEntry() })
    }

    /** The prefix supplier is only valid during the predicate; no entry stream escapes enumeration. */
    fun selectEntries(
        checkCancelled: () -> Unit = {},
        predicate: (ArchiveEntry, () -> InputStream) -> Boolean,
    ): List<ArchiveEntry> = openStream(checkCancelled).use { stream ->
        buildList {
            while (true) {
                checkCancelled()
                val entry = stream.getNextEntry() ?: break
                var prefix: ByteArray? = null
                if (predicate(entry) {
                        val bytes = prefix ?: run {
                            val buffer = ByteArray(MAX_PROBE_BYTES)
                            var count = 0
                            while (count < buffer.size) {
                                checkCancelled()
                                val read = stream.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            buffer.copyOf(count).also { prefix = it }
                        }
                        ByteArrayInputStream(bytes)
                    }
                ) {
                    add(entry)
                }
            }
        }
    }

    /**
     * Opens [entryName] for reading, walking forward from wherever the retained session already is.
     * The returned stream owns its position; closing it hands the session back for the next entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        val target = entryPosition(entryName) ?: return null
        val positioned = positionAt(target) ?: return null
        return EntryStream(positioned)
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            streams.toList().also { streams.clear() }
        }
        synchronized(cursorLock) {
            cursor = null
            cursorIndex = -1
            cursorBusy = false
        }
        try {
            var failure: Throwable? = null
            active.forEach { stream ->
                try {
                    stream.close()
                } catch (error: Throwable) {
                    val existing = failure
                    if (existing == null) failure = error else existing.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        } finally {
            descriptor.close()
        }
    }

    private fun entryPosition(entryName: String): Int? = entryPositions()[entryName]

    private fun entryPositions(): Map<String, Int> {
        entryIndex?.let { return it }
        return synchronized(entryIndexLock) {
            entryIndex ?: useEntries { entries ->
                val positions = HashMap<String, Int>()
                // A duplicate name resolves to its first occurrence, matching a sequential walk.
                entries.forEachIndexed { position, entry ->
                    if (entry.name !in positions) positions[entry.name] = position
                }
                positions
            }.also { entryIndex = it }
        }
    }

    private fun positionAt(target: Int): PositionedStream? {
        val retained = synchronized(cursorLock) {
            val existing = cursor
            // A session can only serve entries ahead of the one it last handed out: its current
            // entry has already been consumed, and headers cannot be walked backwards.
            if (existing == null || cursorBusy || cursorIndex >= target) {
                null
            } else {
                cursorBusy = true
                // A header walk that fails on a reused session must not surface as a page error:
                // drop the session and let the fresh pass below decide, exactly as before.
                if (runCatching { advance(existing, cursorIndex + 1, target) }.getOrDefault(false)) {
                    return PositionedStream(existing, target, retained = true)
                }
                cursor = null
                cursorIndex = -1
                cursorBusy = false
                existing
            }
        }
        // The reused session could not walk to the target; drop it and answer from a fresh one.
        if (retained != null) runCatching { retained.close() }
        val fresh = openStream()
        val advanced = try {
            advance(fresh, 0, target)
        } catch (error: Throwable) {
            runCatching { fresh.close() }
            throw error
        }
        if (!advanced) {
            fresh.close()
            return null
        }
        return PositionedStream(fresh, target, retained = false)
    }

    /** Reads headers until [target] has been reached, starting from the [from]-th unread header. */
    private fun advance(stream: ArchiveInputStream, from: Int, target: Int): Boolean {
        var position = from
        while (position <= target) {
            stream.getNextEntry() ?: return false
            position++
        }
        return true
    }

    private fun release(positioned: PositionedStream, failed: Boolean) {
        var keep = false
        synchronized(cursorLock) {
            if (positioned.retained) cursorBusy = false
            // Decoders stop at the end of the image rather than the end of the entry, so "not read
            // to EOF" is the normal case and must not throw the session away.
            keep = positioned.retained && !failed && !isClosed()
            if (keep) {
                cursor = positioned.stream
                cursorIndex = positioned.index
            } else if (positioned.retained) {
                cursor = null
                cursorIndex = -1
            }
        }
        when {
            keep -> Unit
            // A failed read leaves the native session in an unknown state; closing it must not
            // hide the error the caller is already unwinding with.
            failed -> runCatching { positioned.stream.close() }
            else -> positioned.stream.close()
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun openStream(checkCancelled: () -> Unit = {}): ArchiveInputStream = synchronized(lock) {
        if (closed) throw IOException("Archive reader is closed")
        val owned = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        try {
            ArchiveInputStream(owned, checkCancelled) { stream ->
                synchronized(lock) { streams.remove(stream) }
            }.also { streams.add(it) }
        } catch (error: Throwable) {
            owned.close()
            throw error
        }
    }

    private class PositionedStream(
        val stream: ArchiveInputStream,
        val index: Int,
        val retained: Boolean,
    )

    private inner class EntryStream(private val positioned: PositionedStream) : InputStream() {
        private var released = false
        private var failed = false

        override fun read(): Int {
            // The session may outlive this stream now, so a late read must not walk into whatever
            // entry the next request left it at.
            if (released) throw IOException("Entry stream is closed")
            return track { positioned.stream.read() }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (released) throw IOException("Entry stream is closed")
            return track { positioned.stream.read(b, off, len) }
        }

        override fun available(): Int = positioned.stream.available()

        override fun close() {
            if (released) return
            released = true
            release(positioned, failed)
        }

        private inline fun track(block: () -> Int): Int = try {
            block()
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    private companion object {
        const val MAX_PROBE_BYTES = 64 * 1024
    }
}
