package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import koharia.connection.ConnectionPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Picks which libraries stay out of the library shelf bar.
 *
 * Hiding is a display filter only: the library keeps its entries, its downloads and its history, it
 * just stops appearing as its own page and stops contributing rows to the aggregate page.
 */
class ConnectionLibraryHiddenScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val profiles by remember(connectionPreferences) { connectionPreferences.profilesChanges() }
            .collectAsState(initial = connectionPreferences.getProfiles())
        val hiddenIds by remember(connectionPreferences) { connectionPreferences.hiddenLibraryIdsChanges() }
            .collectAsState(initial = connectionPreferences.hiddenLibraryIds())

        fun setHidden(id: Long, hidden: Boolean) {
            val ids = connectionPreferences.hiddenLibraryIds().toMutableSet()
            if (hidden) ids += id else ids -= id
            connectionPreferences.setHiddenLibraryIds(ids)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.connection_library_hidden_libraries),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    Text(
                        text = stringResource(MR.strings.connection_library_hidden_libraries_summary),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (profiles.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.connection_library_hidden_libraries_empty),
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(
                    items = profiles,
                    key = { it.id },
                ) { profile ->
                    // A tick means "hide this library": the list is titled after the hidden set, so
                    // the box has to follow the same polarity as the copy above it.
                    val hidden = profile.id in hiddenIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setHidden(profile.id, !hidden) }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = hidden,
                            onCheckedChange = { setHidden(profile.id, it) },
                        )
                        Text(
                            text = profile.name,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
