package koharia.source.local

import koharia.connection.ConnectionLibraryRefreshResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Persists the library configuration.
 *
 * [scan] is optional on purpose. A full scan of a large library runs for tens of minutes and the
 * scan knobs live on the screen that just closed, so production leaves it to the shelf's own scan
 * entry point: the saved library reports [LocalFolderSource.needsInitialScan] as true and the owner
 * starts the import once the settings are where they want them.
 */
internal suspend fun saveLocalLibraryConfiguration(
    preferences: LocalLibraryPreferences,
    config: LocalLibraryConfig,
    assignments: Map<String, String>,
    persist: suspend () -> Unit = { preferences.saveLibraryDraft(config.copy(setupCompleted = true), assignments) },
    scan: (suspend () -> Result<ConnectionLibraryRefreshResult>)? = null,
): Result<ConnectionLibraryRefreshResult>? = withContext(NonCancellable) {
    val newlyConfigured = !preferences.getConfig().setupCompleted
    persist()
    if (newlyConfigured && scan != null) runCatching { scan().getOrThrow() } else null
}
