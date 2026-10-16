/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.sharing.rememberNativeDocuments
import bes.max.bmaps.feature.viewer.history.presentation.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onReview: (String) -> Unit, onRecord: () -> Unit, onFollow: (String) -> Unit, onOpenActive: () -> Unit = onRecord) {
    val model = metroViewModel<HistoryViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val documents = rememberNativeDocuments({ it.dispose() }, model::nativeFailure)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, documents) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.refresh()
            model.events.collect { event -> when (event) {
                is HistoryEvent.Share -> documents.share(event.document)
                is HistoryEvent.Message -> snackbar.showSnackbar(getString(event.message))
            } }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(Res.string.history_title)) },
        navigationIcon = { TextButton(onBack) { Text(stringResource(Res.string.viewer_back)) } },
        actions = { TextButton(onRecord) { Text(stringResource(Res.string.history_new_from_map)) } }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row {
                FilterChip(!state.routesTab, { model.tab(false) }, label = { Text(stringResource(Res.string.history_activities)) }, enabled = !state.busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(state.routesTab, { model.tab(true) }, label = { Text(stringResource(Res.string.history_routes)) }, enabled = !state.busy)
            }
            OutlinedTextField(state.search, model::search, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.history_search)) }, singleLine = true)
            if (!state.routesTab) HistoryFilterList(state.filter, model::filter)
            state.activeId?.let { TextButton(onOpenActive) { Text(stringResource(Res.string.history_open_active)) } }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.routesTab) SavedRouteList(state.routes, state.busy, state.more, model::more, onFollow,
                model::editRoute, { model.confirmDelete(it, true) }, { model.exportOptions(it, true) }, Modifier.weight(1f))
            else ActivityHistoryList(state.activities, state.busy, state.more, model::more, onReview, onOpenActive,
                model::editActivity, { model.confirmDelete(it) }, model::derive, { model.exportOptions(it) }, Modifier.weight(1f))
        }
    }
    HistoryDialogs(state, model::draft, model::rename, model::dismissEdit, model::delete, { model.confirmDelete(null) }, model::export, { model.exportOptions(null) })
}

@Composable
private fun HistoryFilterList(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Res.string.history_all, Res.string.history_completed, Res.string.history_interrupted).forEachIndexed { index, label ->
            HistoryFilterItem(index, selected == index, stringResource(label), onSelect)
        }
    }
}
@Composable
private fun HistoryFilterItem(index: Int, selected: Boolean, label: String, onSelect: (Int) -> Unit) {
    FilterChip(selected, { onSelect(index) }, label = { Text(label) })
}

@Composable
private fun ActivityHistoryList(items: ImmutableList<HistoryItem>, busy: Boolean, more: Boolean, onMore: () -> Unit,
    onReview: (String) -> Unit, onActive: () -> Unit, onRename: (String) -> Unit, onDelete: (String) -> Unit,
    onDerive: (String) -> Unit, onExport: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (items.isEmpty() && !busy) item { Text(stringResource(Res.string.history_empty)) }
        items(items, key = { it.id }) { item -> ActivityHistoryItem(item, busy, onReview, onActive, onRename, onDelete, onDerive, onExport) }
        if (more) item { TextButton(onMore, enabled = !busy) { Text(stringResource(Res.string.track_more_routes)) } }
    }
}
@Composable
private fun ActivityHistoryItem(item: HistoryItem, busy: Boolean, onReview: (String) -> Unit, onActive: () -> Unit,
    onRename: (String) -> Unit, onDelete: (String) -> Unit, onDerive: (String) -> Unit, onExport: (String) -> Unit) {
    Card(onClick = { if (item.exportable) onReview(item.id) else onActive() }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.name.ifBlank { stringResource(Res.string.track_unnamed) }, style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(item.status)} · ${stringResource(item.profile)}", style = MaterialTheme.typography.bodySmall)
            Text(item.date, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (item.active) TextButton(onActive, enabled = !busy) { Text(stringResource(Res.string.history_open_active)) }
                if (item.editable) {
                    TextButton({ onRename(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_rename)) }
                    TextButton({ onDerive(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_save_route)) }
                    TextButton({ onDelete(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_delete)) }
                }
                if (item.exportable) TextButton({ onExport(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_export)) }
            }
        }
    }
}

@Composable
private fun SavedRouteList(items: ImmutableList<SavedRouteItem>, busy: Boolean, more: Boolean, onMore: () -> Unit,
    onFollow: (String) -> Unit, onRename: (String) -> Unit, onDelete: (String) -> Unit, onExport: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (items.isEmpty() && !busy) item { Text(stringResource(Res.string.track_no_routes)) }
        items(items, key = { it.id }) { item -> SavedRouteItemRow(item, busy, onFollow, onRename, onDelete, onExport) }
        if (more) item { TextButton(onMore, enabled = !busy) { Text(stringResource(Res.string.track_more_routes)) } }
    }
}
@Composable
private fun SavedRouteItemRow(item: SavedRouteItem, busy: Boolean, onFollow: (String) -> Unit,
    onRename: (String) -> Unit, onDelete: (String) -> Unit, onExport: (String) -> Unit) {
    Card(onClick = { onFollow(item.id) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
        Column(Modifier.padding(12.dp)) {
            Text(item.name.ifBlank { stringResource(Res.string.track_unnamed) }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.track_route_summary, item.segments, item.points), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ onRename(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_rename)) }
                TextButton({ onExport(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_export)) }
                TextButton({ onDelete(item.id) }, enabled = !busy) { Text(stringResource(Res.string.history_delete)) }
            }
        }
    }
}

@Composable
private fun HistoryDialogs(state: HistoryState, onDraft: (String) -> Unit, onRename: () -> Unit, onCancelRename: () -> Unit,
    onDelete: () -> Unit, onCancelDelete: () -> Unit, onExport: (Int) -> Unit, onCancelExport: () -> Unit) {
    if (state.editingId != null) AlertDialog(onDismissRequest = onCancelRename,
        title = { Text(stringResource(Res.string.history_rename)) }, text = { Column {
            OutlinedTextField(state.draftName, onDraft, singleLine = true, enabled = !state.busy)
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onRename, enabled = !state.busy) { Text(stringResource(Res.string.history_save)) } },
        dismissButton = { TextButton(onCancelRename, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } })
    if (state.deletingId != null) AlertDialog(onDismissRequest = onCancelDelete,
        title = { Text(stringResource(Res.string.history_delete)) }, text = { Column {
            Text(stringResource(if (state.deletingRoute) Res.string.history_delete_route_message else Res.string.history_delete_activity_message))
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onDelete, enabled = !state.busy) { Text(stringResource(Res.string.history_delete)) } },
        dismissButton = { TextButton(onCancelDelete, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } })
    if (state.exportingId != null) ActivityExportDialog(state.exportingRoute, state.busy, state.error, onExport, onCancelExport)
}

@Composable
internal fun ActivityExportDialog(route: Boolean, busy: Boolean, error: org.jetbrains.compose.resources.StringResource?,
    onExport: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.history_export)) },
        text = { Column {
            Text(stringResource(Res.string.history_gpx_payload))
            TextButton({ onExport(0) }, enabled = !busy) { Text(stringResource(Res.string.history_export_gpx)) }
            if (!route) {
                Text(stringResource(Res.string.history_json_payload))
                TextButton({ onExport(1) }, enabled = !busy) { Text(stringResource(Res.string.history_export_json)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onDismiss) { Text(stringResource(if (busy) Res.string.history_cancel_export else Res.string.layers_cancel)) } })
}
