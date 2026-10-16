/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.ui.components.MapIcons
import bes.max.bmaps.feature.viewer.history.presentation.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityListScreen(onBack: () -> Unit, onDetails: (String) -> Unit, onOpenActive: () -> Unit,
    onRecord: () -> Unit, onSavedRoutes: () -> Unit) {
    val model = metroViewModel<HistoryViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        model.filter(1)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.refresh() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(Res.string.history_title)) },
            navigationIcon = { IconButton(onBack) { Icon(painterResource(MapIcons.back), stringResource(Res.string.viewer_back)) } },
            actions = { TextButton(onSavedRoutes) { Text(stringResource(Res.string.history_routes)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.activeItem?.let { ActiveActivityItem(it, onOpenActive) }
            OutlinedTextField(state.search, model::search, Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.history_search)) }, singleLine = true)
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(model::refresh, enabled = !state.busy) { Text(stringResource(Res.string.viewer_retry)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            RecordedActivityList(state.activities, state.busy, state.more, model::more, onDetails, onRecord, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecordedActivityList(items: ImmutableList<HistoryItem>, busy: Boolean, more: Boolean,
    onMore: () -> Unit, onDetails: (String) -> Unit, onRecord: () -> Unit, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (items.isEmpty() && !busy) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.history_completed_empty))
                TextButton(onRecord) { Text(stringResource(Res.string.history_new_from_map)) }
            }
        }
        items(items, key = { it.id }) { item -> RecordedActivityItem(item, onDetails) }
        if (more) item { TextButton(onMore, enabled = !busy) { Text(stringResource(Res.string.history_more_activities)) } }
    }
}

@Composable
private fun RecordedActivityItem(item: HistoryItem, onDetails: (String) -> Unit) {
    Card(onClick = { onDetails(item.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(item.profile), style = MaterialTheme.typography.bodyMedium)
            Text(item.date, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActiveActivityItem(item: HistoryItem, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.history_active_recording), style = MaterialTheme.typography.labelMedium)
            Text(item.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(item.status)} · ${stringResource(item.profile)}", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.history_open_active), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
