/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import kotlinx.collections.immutable.ImmutableList
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.activity.presentation.*
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

internal class TrackActions(
    val toggle: () -> Unit, val collapse: () -> Unit, val record: () -> Unit, val routes: () -> Unit,
    val selectRoute: (String) -> Unit, val moreRoutes: () -> Unit, val importGpx: () -> Unit,
    val profile: (Int) -> Unit, val reverse: (Boolean) -> Unit, val segment: (Int) -> Unit, val edge: (Int) -> Unit,
    val start: () -> Unit, val pauseResume: () -> Unit, val finish: () -> Unit, val confirmFinish: (Boolean) -> Unit,
    val menu: (Boolean) -> Unit, val returnRecording: () -> Unit, val followPlanned: () -> Unit,
    val correct: () -> Unit, val applyCorrection: () -> Unit, val toggleAlerts: () -> Unit,
    val followPosition: () -> Unit, val inspect: () -> Unit, val showSelection: () -> Unit,
    val mark: () -> Unit, val recover: () -> Unit, val history: () -> Unit, val details: (Boolean) -> Unit,
)

@Composable
internal fun TrackPreparationDialog(state: TrackState, actions: TrackActions) {
    if (!state.expanded || (state.metrics != null && !state.correcting)) return
    AlertDialog(
        onDismissRequest = { if (!state.busy) actions.collapse() },
        title = { Text(stringResource(if (state.correcting) Res.string.track_correct_section else Res.string.track_start_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.choosing -> TrackRouteList(state.routes, state.moreRoutes, state.busy, actions.selectRoute, actions.moreRoutes, actions.importGpx)
                    state.preparing || state.correcting -> {
                        TrackPreparation(state, actions)
                        if (!state.correcting) {
                            TextButton(actions.routes, enabled = !state.busy) { Text(stringResource(Res.string.track_follow_route)) }
                            if (state.route != null) TextButton(actions.record, enabled = !state.busy) { Text(stringResource(Res.string.track_record)) }
                        }
                    }
                    else -> TrackStartMenu(state.busy, actions.record, actions.routes, actions.history)
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.preparing || state.correcting) TextButton(
                if (state.correcting) actions.applyCorrection else actions.start, enabled = !state.busy,
            ) { Text(stringResource(if (state.correcting) Res.string.track_apply_section else Res.string.track_start)) }
        },
        dismissButton = {
            TextButton(actions.collapse, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) }
        },
    )
}

@Composable
private fun TrackStartMenu(busy: Boolean, onRecord: () -> Unit, onRoutes: () -> Unit, onHistory: () -> Unit) {
    TextButton(onRecord, enabled = !busy) { Text(stringResource(Res.string.track_record)) }
    TextButton(onRoutes, enabled = !busy) { Text(stringResource(Res.string.track_follow_route)) }
    TextButton(onHistory, enabled = !busy) { Text(stringResource(Res.string.history_title)) }
}

@Composable
private fun TrackRouteList(items: ImmutableList<TrackRouteItem>, more: Boolean, busy: Boolean,
    onSelect: (String) -> Unit, onMore: () -> Unit, onImport: () -> Unit) {
    TextButton(onImport, enabled = !busy) { Text(stringResource(Res.string.track_import_gpx)) }
    if (items.isEmpty()) Text(stringResource(Res.string.track_no_routes), style = MaterialTheme.typography.bodySmall)
    items.forEach { TrackRouteRow(it, busy, onSelect) }
    if (more) TextButton(onMore, enabled = !busy) { Text(stringResource(Res.string.track_more_routes)) }
}

@Composable
private fun TrackRouteRow(item: TrackRouteItem, busy: Boolean, onSelect: (String) -> Unit) {
    TextButton({ onSelect(item.id) }, enabled = !busy) {
        Column {
            Text(item.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.track_route_summary, item.segments, item.vertices), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrackPreparation(state: TrackState, actions: TrackActions) {
    state.route?.let { route ->
        Text(route.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(Res.string.track_route_length, route.length), style = MaterialTheme.typography.bodySmall)
        if (route.segments > 1) Text(stringResource(Res.string.track_gap_warning), style = MaterialTheme.typography.bodySmall)
        if (state.coverageMissing) Text(stringResource(Res.string.track_route_coverage), style = MaterialTheme.typography.bodySmall)
    }
    if (!state.correcting) {
        TrackProfileList(state.profile, !state.busy, actions.profile)
        if (state.route != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!state.reverse, { actions.reverse(false) }, label = { Text(stringResource(Res.string.track_forward)) }, enabled = !state.busy)
            FilterChip(state.reverse, { actions.reverse(true) }, label = { Text(stringResource(Res.string.track_reverse)) }, enabled = !state.busy)
        }
    }
    if (state.route != null) {
        Text(stringResource(Res.string.track_start_distance, state.startDistance))
        TextButton(actions.showSelection, enabled = !state.busy) { Text(stringResource(Res.string.track_pick_on_map)) }
        if (!state.correcting) TextButton({ actions.reverse(state.reverse) }, enabled = !state.busy) {
            Text(stringResource(Res.string.track_start_at_beginning))
        }
    }
    if (!state.correcting) Text(stringResource(Res.string.track_phone_only), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TrackProfileList(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Res.string.track_hiking, Res.string.track_motorcycle, Res.string.track_road).forEachIndexed { index, label ->
            TrackProfileItem(index, label, selected == index, enabled, onSelect)
        }
    }
}

@Composable
private fun TrackProfileItem(index: Int, label: StringResource, selected: Boolean, enabled: Boolean, onSelect: (Int) -> Unit) {
    FilterChip(selected, { onSelect(index) }, label = { Text(stringResource(label)) }, enabled = enabled)
}

@Composable
internal fun TrackStartPicker(state: TrackState, onConfirm: () -> Unit, onCancel: () -> Unit,
    onNext: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 360.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.track_pick_hint), style = MaterialTheme.typography.titleSmall)
            if (state.pickLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.pickOptions.isNotEmpty()) Text(stringResource(Res.string.track_start_distance, state.startDistance))
            if (state.pickOptions.size > 1) TextButton(onNext, enabled = !state.pickLoading) {
                Text(stringResource(Res.string.track_pick_alternative, state.pickOption + 1, state.pickOptions.size))
            }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onCancel) { Text(stringResource(Res.string.layers_cancel)) }
                TextButton(onConfirm, enabled = !state.pickLoading && state.pickOptions.isNotEmpty()) {
                    Text(stringResource(Res.string.track_pick_confirm))
                }
            }
        }
    }
}

@Composable
internal fun TrackOverflow(expanded: Boolean, metrics: TrackMetrics, actions: TrackActions) {
    DropdownMenu(expanded, { actions.menu(false) }) {
        TrackMenuItem(Res.string.track_finish, enabled = !metrics.failed) { actions.menu(false); actions.confirmFinish(true) }
        if (metrics.recording) TrackMenuItem(Res.string.track_mark_point) { actions.menu(false); actions.mark() }
        TrackMenuItem(Res.string.history_title) { actions.menu(false); actions.history() }
        TrackMenuItem(Res.string.track_metrics) { actions.menu(false); actions.details(true) }
        if (metrics.hasNavigation && !metrics.failed && !metrics.finalizing) {
            TrackMenuItem(Res.string.track_correct_section) { actions.menu(false); actions.correct() }
            TrackMenuItem(if (metrics.alertsEnabled) Res.string.track_disable_alerts else Res.string.track_enable_alerts) { actions.menu(false); actions.toggleAlerts() }
        }
        if (!metrics.failed && !metrics.finalizing) TrackMenuItem(Res.string.track_return_recorded) { actions.menu(false); actions.returnRecording() }
        if (metrics.returning && metrics.hasPlan && !metrics.failed && !metrics.finalizing) TrackMenuItem(Res.string.track_restore_plan) { actions.menu(false); actions.followPlanned() }
    }
}

@Composable
private fun TrackMenuItem(label: StringResource, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, enabled = enabled)
}

@Composable
internal fun TrackDialogs(state: TrackState, actions: TrackActions) {
    if (state.finishConfirmation) AlertDialog(
        onDismissRequest = { if (!state.busy) actions.confirmFinish(false) },
        title = { Text(stringResource(Res.string.track_finish)) },
        text = { Column {
            Text(stringResource(Res.string.track_finish_message))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(actions.finish, enabled = !state.busy) { Text(stringResource(Res.string.track_finish)) } },
        dismissButton = { TextButton({ actions.confirmFinish(false) }, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } },
    )
    if (state.details && state.metrics != null) AlertDialog(
        onDismissRequest = { actions.details(false) }, title = { Text(stringResource(Res.string.track_metrics)) },
        text = { TrackMetricDetails(state.metrics) },
        confirmButton = { TextButton({ actions.details(false) }) { Text(stringResource(Res.string.viewer_done)) } },
    )
}

@Composable
private fun TrackMetricDetails(metrics: TrackMetrics) {
    Column {
        Text(stringResource(Res.string.track_distance, metrics.distance))
        Text(stringResource(Res.string.track_elapsed, metrics.elapsed))
        Text(stringResource(Res.string.track_recording_time, metrics.recordingTime))
        Text(stringResource(Res.string.track_moving_time, metrics.movingTime))
        Text(stringResource(Res.string.track_heart_rate, metrics.heartRate))
        Text(metrics.primary)
    }
}
