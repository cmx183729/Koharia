package koharia.connection.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ConnectionLibraryAllFilterSheet(
    owners: List<Pair<Long, String>>,
    sortOptions: List<Pair<ConnectionLibrarySort, String>>,
    filters: ConnectionLibraryFilters,
    onDismiss: () -> Unit,
    onApply: (ConnectionLibraryFilters) -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    val ownerChoices = listOf<Pair<Long?, String>>(
        null to stringResource(MR.strings.connection_library_all_connections),
    ) + owners.map { (id, label) -> id to label }
    val ownerIndex = ownerChoices.indexOfFirst { it.first == draft.ownerSourceId }.coerceAtLeast(0)
    val sortIndex = sortOptions.indexOfFirst { it.first == draft.sort }.coerceAtLeast(0)

    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            SelectItem(
                stringResource(MR.strings.connection_library_filter_owner),
                ownerChoices.map { it.second }.toTypedArray(),
                ownerIndex,
            ) { draft = draft.copy(ownerSourceId = ownerChoices[it].first) }
            CheckboxItem(stringResource(MR.strings.connection_library_filter_favorites), draft.favoritesOnly) {
                draft = draft.copy(favoritesOnly = !draft.favoritesOnly)
            }
            CheckboxItem(stringResource(MR.strings.connection_library_filter_completed), draft.completedOnly) {
                draft = draft.copy(completedOnly = !draft.completedOnly)
            }
            CheckboxItem(stringResource(MR.strings.connection_library_filter_downloaded), draft.downloadedOnly) {
                draft = draft.copy(downloadedOnly = !draft.downloadedOnly)
            }
            SelectItem(
                stringResource(MR.strings.action_sort),
                sortOptions.map { it.second }.toTypedArray(),
                sortIndex,
            ) { draft = draft.copy(sort = sortOptions[it].first) }
            CheckboxItem(stringResource(MR.strings.shelf_sort_descending), !draft.ascending) {
                draft = draft.copy(ascending = !draft.ascending)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { draft = ConnectionLibraryFilters() }) {
                    Text(stringResource(MR.strings.action_reset))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(draft) }) {
                    Text(stringResource(MR.strings.action_apply))
                }
            }
        }
    }
}
