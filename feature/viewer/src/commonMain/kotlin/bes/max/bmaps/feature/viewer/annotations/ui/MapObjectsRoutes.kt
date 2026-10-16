/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.annotations.presentation.*
import bmaps.feature.viewer.generated.resources.*
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

internal class MapObjectsActions(
    val pick: () -> Unit, val beginImport: () -> Unit, val text: (String) -> Unit,
    val prepare: () -> Unit, val confirm: () -> Unit, val close: () -> Unit, val cancel: () -> Unit,
    val more: () -> Unit, val refresh: () -> Unit, val select: (String) -> Unit,
    val follow: ((String) -> Unit)?, val followLine: ((String) -> Unit)?,
)

@Composable
internal fun MapObjectRouteList(lines: ImmutableList<MapRouteItem>, state: MapObjectsState,
    actions: MapObjectsActions, busy: Boolean, onSelectLine: (String) -> Unit) {
    Text(stringResource(Res.string.objects_routes), style = MaterialTheme.typography.labelMedium)
    lines.forEach { MapObjectRouteRow(it, busy, onSelectLine, actions.followLine) }
    state.routes.forEach { MapObjectRouteRow(it, busy, actions.select, actions.follow) }
    if (lines.isEmpty() && state.routes.isEmpty() && !state.loading) Text(stringResource(Res.string.track_no_routes))
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.routeError?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
        TextButton(actions.refresh) { Text(stringResource(Res.string.viewer_retry)) }
    }
    if (state.more) TextButton(actions.more, enabled = !state.loading) { Text(stringResource(Res.string.track_more_routes)) }
}

@Composable
private fun MapObjectRouteRow(item: MapRouteItem, busy: Boolean, onSelect: (String) -> Unit,
    onFollow: ((String) -> Unit)?) {
    Row(Modifier.fillMaxWidth()) {
        TextButton({ onSelect(item.id) }, Modifier.weight(1f), enabled = !busy) {
            Column {
                Text(item.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(Res.string.track_route_summary, item.segments, item.vertices), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onFollow != null) TextButton({ onFollow(item.id) }, enabled = !busy) {
            Text(stringResource(Res.string.track_start))
        }
    }
}

@Composable
internal fun MapObjectsImportDialog(state: MapObjectsState, actions: MapObjectsActions) {
    if (!state.importOpen) return
    AlertDialog(
        onDismissRequest = actions.close,
        title = { Text(stringResource(Res.string.annotations_import)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.annotations_import_hint))
                if (state.prepared) Text(stringResource(Res.string.objects_import_summary,
                    state.routeCount, state.objectCount, state.pointCount))
                else {
                    TextButton(actions.pick, enabled = !state.busy) { Text(stringResource(Res.string.annotations_pick_file)) }
                    OutlinedTextField(state.text, actions.text, enabled = !state.busy,
                        label = { Text(stringResource(Res.string.annotations_geojson)) })
                }
                if (state.partial) Text(stringResource(Res.string.objects_import_partial), color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(actions.cancel) { Text(stringResource(Res.string.layers_cancel)) }
                }
            }
        },
        confirmButton = {
            TextButton(if (state.prepared) actions.confirm else actions.prepare,
                enabled = !state.busy && (state.prepared || state.text.isNotBlank())) {
                Text(stringResource(if (state.prepared) Res.string.annotations_import else Res.string.objects_prepare_import))
            }
        },
        dismissButton = {
            TextButton(actions.close, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) }
        },
    )
}

@Composable
internal fun MapRouteCard(selection: MapRouteSelection, onClose: () -> Unit, onFollow: (() -> Unit)?,
    modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 260.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(selection.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(Res.string.track_route_length, selection.length), style = MaterialTheme.typography.bodySmall)
            Row {
                if (onFollow != null) TextButton(onFollow) { Text(stringResource(Res.string.track_start)) }
                TextButton(onClose) { Text(stringResource(Res.string.viewer_done)) }
            }
        }
    }
}
