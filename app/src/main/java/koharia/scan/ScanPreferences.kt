package koharia.scan

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class ScanPreferences(preferenceStore: PreferenceStore) {
    val webDavConcurrency: Preference<Int> =
        preferenceStore.getInt(KEY_WEBDAV_CONCURRENCY, DEFAULT_WEBDAV_CONCURRENCY)
    val webDavRequestIntervalMs: Preference<Int> =
        preferenceStore.getInt(KEY_WEBDAV_REQUEST_INTERVAL_MS, DEFAULT_WEBDAV_REQUEST_INTERVAL_MS)
    val scraperRequestIntervalMs: Preference<Int> =
        preferenceStore.getInt(KEY_SCRAPER_REQUEST_INTERVAL_MS, DEFAULT_SCRAPER_REQUEST_INTERVAL_MS)
    val incrementalScan: Preference<Boolean> =
        preferenceStore.getBoolean(KEY_INCREMENTAL_SCAN, true)
    val readerPreloadPages: Preference<Int> =
        preferenceStore.getInt(KEY_READER_PRELOAD_PAGES, DEFAULT_READER_PRELOAD_PAGES)

    companion object {
        const val DEFAULT_WEBDAV_CONCURRENCY = 3
        const val MIN_WEBDAV_CONCURRENCY = 1
        const val MAX_WEBDAV_CONCURRENCY = 16
        const val DEFAULT_WEBDAV_REQUEST_INTERVAL_MS = 200
        const val DEFAULT_SCRAPER_REQUEST_INTERVAL_MS = 1_500
        const val MAX_REQUEST_INTERVAL_MS = 5_000
        const val REQUEST_INTERVAL_STEP_MS = 100
        const val DEFAULT_READER_PRELOAD_PAGES = 5
        const val MIN_READER_PRELOAD_PAGES = 1
        const val MAX_READER_PRELOAD_PAGES = 20

        private const val KEY_WEBDAV_CONCURRENCY = "scan_webdav_concurrency"
        private const val KEY_WEBDAV_REQUEST_INTERVAL_MS = "scan_webdav_request_interval_ms"
        private const val KEY_SCRAPER_REQUEST_INTERVAL_MS = "scan_scraper_request_interval_ms"
        private const val KEY_INCREMENTAL_SCAN = "scan_incremental_enabled"
        private const val KEY_READER_PRELOAD_PAGES = "reader_preload_pages"
    }
}
