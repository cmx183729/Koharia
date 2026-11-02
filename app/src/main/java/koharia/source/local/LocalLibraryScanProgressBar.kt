package koharia.source.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * The line of text that describes [progress]. Only the import phase knows how many entries are
 * left, so it is the only one that reports a percentage and a count.
 */
@Composable
fun localLibraryScanProgressLabel(progress: LocalLibraryScanProgress): String = when (progress.phase) {
    LocalLibraryScanProgress.Phase.DISCOVERING ->
        stringResource(MR.strings.local_library_scan_progress_discovering)
    LocalLibraryScanProgress.Phase.INDEXING -> if (progress.isDeterminate) {
        stringResource(
            MR.strings.local_library_scan_progress_indexing_percent,
            (progress.fraction * 100).roundToInt(),
            progress.completed,
            progress.total,
        )
    } else {
        stringResource(
            MR.strings.local_library_scan_progress_indexing,
            progress.completed,
            progress.total,
        )
    }
    LocalLibraryScanProgress.Phase.FINISHING ->
        stringResource(MR.strings.local_library_scan_progress_finishing)
}

/**
 * Progress of a library import, shown while a scan is still running.
 *
 * [LocalLibraryScanProgress.Phase.DISCOVERING] and
 * [LocalLibraryScanProgress.Phase.FINISHING] have no item count to report, so they render an
 * indeterminate bar; only the import phase can show a real fraction.
 */
@Composable
fun LocalLibraryScanProgressBar(
    progress: LocalLibraryScanProgress,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = localLibraryScanProgressLabel(progress),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        LocalLibraryScanProgressTrack(progress = progress, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Compact variant for a list row: the same report without the surrounding padding, so a library
 * entry in the connection list can show how far its import got.
 */
@Composable
fun LocalLibraryScanProgressInline(
    progress: LocalLibraryScanProgress,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = localLibraryScanProgressLabel(progress),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LocalLibraryScanProgressTrack(progress = progress, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun LocalLibraryScanProgressTrack(progress: LocalLibraryScanProgress, modifier: Modifier) {
    if (progress.isDeterminate) {
        EInkLinearProgressIndicator(
            progress = { progress.fraction },
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        EInkLinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}
