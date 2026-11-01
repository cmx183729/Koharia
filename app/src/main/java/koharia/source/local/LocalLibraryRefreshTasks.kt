package koharia.source.local

import koharia.connection.ConnectionLibraryRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Explicit scans belong to the application, not the screen waiting for their result. */
class LocalLibraryRefreshTasks(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val tasks = mutableMapOf<Long, Deferred<Result<ConnectionLibraryRefreshResult>>>()

    /**
     * Lazily resolved because this class is itself built by the injector; the scan reports its own
     * progress and this only has to drop the last value once the scan is over, whatever ended it.
     * Null when there is no injector, which is the case in unit tests.
     */
    private val scanProgress by lazy {
        runCatching { Injekt.get<LocalLibraryScanProgressTracker>() }.getOrNull()
    }
    private val active = MutableStateFlow(emptySet<Long>())
    val activeIds = active.asStateFlow()
    private val failures = MutableStateFlow<Map<Long, Throwable>>(emptyMap())
    val errors = failures.asStateFlow()

    fun start(id: Long, scan: suspend () -> ConnectionLibraryRefreshResult) {
        scope.launch { refresh(id, scan) }
    }

    suspend fun cancel(id: Long) {
        synchronized(tasks) { tasks[id] }?.cancelAndJoin()
        failures.update { it - id }
    }

    suspend fun refresh(
        id: Long,
        scan: suspend () -> ConnectionLibraryRefreshResult,
    ): Result<ConnectionLibraryRefreshResult> {
        val task = synchronized(tasks) {
            tasks[id]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    Result.success(scan())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures.update { it + (id to error) }
                    Result.failure(error)
                }
            }.also { task ->
                tasks[id] = task
                failures.update { it - id }
                active.update { it + id }
                task.invokeOnCompletion {
                    synchronized(tasks) {
                        if (tasks[id] === task) {
                            tasks.remove(id)
                            active.update { it - id }
                        }
                    }
                    scanProgress?.clear(id)
                }
                task.start()
            }
        }
        return task.await()
    }
}
