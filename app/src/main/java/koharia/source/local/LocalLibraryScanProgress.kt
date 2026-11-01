package koharia.source.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live progress of one library scan.
 *
 * [total] stays at zero while the walk is still discovering entries, which is why the phase is part
 * of the model: the reader renders an indeterminate bar for [Phase.DISCOVERING] and a determinate
 * one once the import step knows how many entries it has to work through.
 */
data class LocalLibraryScanProgress(
    val phase: Phase,
    val completed: Int = 0,
    val total: Int = 0,
) {
    enum class Phase {
        DISCOVERING,
        INDEXING,
        FINISHING,
    }

    val isDeterminate: Boolean get() = total > 0

    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Publishes scan progress per library source.
 *
 * A scan reports once per entry, so updates are throttled: emitting a thousand states for a thousand
 * entries would cost more than the work being reported. Phase changes and the final count always go
 * through, because those are the only two the reader has to see.
 */
class LocalLibraryScanProgressTracker {
    private val states = MutableStateFlow<Map<Long, LocalLibraryScanProgress>>(emptyMap())
    private val lastEmit = mutableMapOf<Long, Long>()

    val progress: StateFlow<Map<Long, LocalLibraryScanProgress>> = states.asStateFlow()

    fun update(sourceId: Long, value: LocalLibraryScanProgress) {
        val previous = states.value[sourceId]
        val finished = value.isDeterminate && value.completed >= value.total
        if (previous?.phase == value.phase && !finished) {
            synchronized(lastEmit) {
                val now = System.currentTimeMillis()
                if (now - (lastEmit[sourceId] ?: 0L) < EMIT_INTERVAL_MS) return
                lastEmit[sourceId] = now
            }
        }
        states.update { it + (sourceId to value) }
    }

    fun clear(sourceId: Long) {
        synchronized(lastEmit) { lastEmit.remove(sourceId) }
        states.update { it - sourceId }
    }

    private companion object {
        const val EMIT_INTERVAL_MS = 80L
    }
}

/**
 * Merges every running scan into one bar, so a screen that lists several libraries at once can show
 * a single progress instead of picking one arbitrarily.
 */
fun Map<Long, LocalLibraryScanProgress>.aggregateScanProgress(): LocalLibraryScanProgress? {
    val indexing = values.filter { it.phase == LocalLibraryScanProgress.Phase.INDEXING }
    if (indexing.isNotEmpty()) {
        return LocalLibraryScanProgress(
            phase = LocalLibraryScanProgress.Phase.INDEXING,
            completed = indexing.sumOf { it.completed },
            total = indexing.sumOf { it.total },
        )
    }
    return values.firstOrNull { it.phase == LocalLibraryScanProgress.Phase.FINISHING }
        ?: values.firstOrNull()
}
