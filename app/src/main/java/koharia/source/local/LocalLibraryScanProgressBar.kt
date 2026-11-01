package koharia.source.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.EInkLinearProgressIndicator
import tachiyomi.presentation.core.i18n.stringResource

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
    val label = when (progress.phase) {
        LocalLibraryScanProgress.Phase.DISCOVERING ->
            stringResource(MR.strings.local_library_scan_progress_discovering)
        LocalLibraryScanProgress.Phase.INDEXING -> stringResource(
            MR.strings.local_library_scan_progress_indexing,
            progress.completed,
            progress.total,
        )
        LocalLibraryScanProgress.Phase.FINISHING ->
            stringResource(MR.strings.local_library_scan_progress_finishing)
    }
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (progress.isDeterminate) {
            EInkLinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        } else {
            EInkLinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}
