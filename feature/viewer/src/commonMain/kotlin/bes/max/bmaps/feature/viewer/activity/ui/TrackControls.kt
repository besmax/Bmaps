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
    val mark: () -> Unit, val recover: () -> Unit, val details: (Boolean) -> Unit,
)

@Composable
internal fun TrackControls(state: TrackState, actions: TrackActions, modifier: Modifier = Modifier, outsideCoverage: Boolean = false) {
    Surface(modifier.widthIn(max = if (state.expanded) 300.dp else 220.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shadowElevation = 2.dp,
    ) {
        if (!state.expanded) {
            TextButton(actions.toggle, Modifier.heightIn(min = 48.dp)) {
                val metrics = state.metrics
                Text(if (state.error != null) stringResource(state.error) else if (metrics == null) stringResource(Res.string.track_title) else
                    "${stringResource(metrics.status)} · ${metrics.elapsed} · ${metrics.primary}",
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.track_title), Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.titleSmall)
                    TextButton(actions.collapse) { Text(stringResource(Res.string.track_collapse)) }
                }
                when {
                    state.choosing -> TrackRouteList(state.routes, state.moreRoutes, state.busy, actions.selectRoute, actions.moreRoutes, actions.importGpx)
                    state.preparing || state.correcting -> TrackPreparation(state, actions)
                    state.metrics != null -> TrackActive(state.metrics, state, actions)
                    else -> TrackStartMenu(state.busy, actions.record, actions.routes)
                }
                if (outsideCoverage) Text(stringResource(Res.string.track_outside_coverage), style = MaterialTheme.typography.bodySmall)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun TrackStartMenu(busy: Boolean, onRecord: () -> Unit, onRoutes: () -> Unit) {
    TextButton(onRecord, enabled = !busy) { Text(stringResource(Res.string.track_record)) }
    TextButton(onRoutes, enabled = !busy) { Text(stringResource(Res.string.track_follow_route)) }
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
    if (state.route != null) TrackSectionSelector(state.segment, state.edge, state.route.segments, state.route.edges,
        !state.busy, actions.segment, actions.edge, actions.showSelection)
    if (!state.correcting) Text(stringResource(Res.string.track_phone_only), style = MaterialTheme.typography.bodySmall)
    Button(if (state.correcting) actions.applyCorrection else actions.start,
        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (state.correcting) Res.string.track_apply_section else Res.string.track_start))
    }
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
private fun TrackSectionSelector(segment: Int, edge: Int, segments: Int, edges: Int, enabled: Boolean,
    onSegment: (Int) -> Unit, onEdge: (Int) -> Unit, onShow: () -> Unit) {
    val previousLabel = stringResource(Res.string.track_previous_segment)
    val nextLabel = stringResource(Res.string.track_next_segment)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ onSegment(segment - 1) }, enabled = enabled && segment > 0,
            modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("−") }
        Text(stringResource(Res.string.track_section, segment + 1, segments), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall)
        TextButton({ onSegment(segment + 1) }, enabled = enabled && segment + 1 < segments,
            modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("+") }
    }
    TrackPositionControls(edge, edges, enabled, onEdge)
    val sectionDescription = stringResource(Res.string.track_edge, edge + 1, edges)
    if (edges > 1) Slider(edge.toFloat(), { onEdge(it.toInt()) }, valueRange = 0f..(edges - 1).toFloat(), enabled = enabled,
        modifier = Modifier.semantics { contentDescription = sectionDescription })
    TextButton(onShow, enabled = enabled) { Text(stringResource(Res.string.track_show_section)) }
}

@Composable
private fun TrackPositionControls(edge: Int, edges: Int, enabled: Boolean, onEdge: (Int) -> Unit) {
    val previous = stringResource(Res.string.track_previous_position)
    val next = stringResource(Res.string.track_next_position)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ onEdge(edge - 1) }, enabled = enabled && edge > 0,
            modifier = Modifier.semantics { contentDescription = previous }) { Text("−") }
        Text(stringResource(Res.string.track_edge, edge + 1, edges), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall)
        TextButton({ onEdge(edge + 1) }, enabled = enabled && edge + 1 < edges,
            modifier = Modifier.semantics { contentDescription = next }) { Text("+") }
    }
}

@Composable
private fun TrackActive(metrics: TrackMetrics, state: TrackState, actions: TrackActions) {
    Text(metrics.name.ifBlank { stringResource(Res.string.track_unnamed) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text("${stringResource(metrics.status)} · ${metrics.elapsed}", style = MaterialTheme.typography.bodySmall)
    Text(metrics.primary, style = MaterialTheme.typography.titleLarge)
    Text(stringResource(Res.string.track_distance, metrics.distance), style = MaterialTheme.typography.bodySmall)
    metrics.remaining?.let { Text(stringResource(Res.string.track_remaining, it), style = MaterialTheme.typography.bodySmall) }
    metrics.deviation?.let { Text(stringResource(Res.string.track_deviation, it), style = MaterialTheme.typography.bodySmall) }
    metrics.controlPoint?.let { Text(stringResource(Res.string.track_next_point, it), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
    Text(stringResource(metrics.quality), style = MaterialTheme.typography.bodySmall)
    metrics.navigationQuality?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    if (metrics.endReached) Text(stringResource(Res.string.track_end_reached), style = MaterialTheme.typography.bodySmall)
    if (metrics.failed) {
        Text(stringResource(Res.string.track_storage_error), color = MaterialTheme.colorScheme.error)
        TextButton(actions.recover, enabled = !state.busy) { Text(stringResource(Res.string.track_recover)) }
    }
    Row {
        if (metrics.recording || metrics.resumable) TextButton(actions.pauseResume, enabled = !state.busy && !metrics.failed) {
            Text(stringResource(if (metrics.recording) Res.string.track_pause else Res.string.track_resume))
        }
        Box {
            TextButton({ actions.menu(true) }, enabled = !state.busy) { Text(stringResource(Res.string.track_actions)) }
            TrackOverflow(state.menu, metrics, actions)
        }
    }
    TextButton(if (state.following) actions.inspect else actions.followPosition, enabled = state.position != null) {
        Text(stringResource(if (state.following) Res.string.track_inspect else Res.string.track_return_position))
    }
    if (metrics.finalizing) TextButton(actions.finish, enabled = !state.busy && !metrics.failed) { Text(stringResource(Res.string.viewer_retry)) }
}

@Composable
private fun TrackOverflow(expanded: Boolean, metrics: TrackMetrics, actions: TrackActions) {
    DropdownMenu(expanded, { actions.menu(false) }) {
        TrackMenuItem(Res.string.track_finish, enabled = !metrics.failed) { actions.menu(false); actions.confirmFinish(true) }
        if (metrics.recording) TrackMenuItem(Res.string.track_mark_point) { actions.menu(false); actions.mark() }
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
