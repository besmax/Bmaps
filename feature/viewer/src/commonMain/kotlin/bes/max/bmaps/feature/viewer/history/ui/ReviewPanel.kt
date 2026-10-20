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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.history.presentation.*
import bmaps.feature.viewer.generated.resources.*
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal fun ReviewPanel(state: ReviewState, onExpand: (Boolean) -> Unit, onSeries: (Int) -> Unit,
    onSelect: (Long, String?) -> Unit, onEvents: (Boolean) -> Unit, onExport: (Boolean) -> Unit,
    onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = if (state.panelExpanded) 340.dp else 220.dp),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
        if (!state.panelExpanded) TextButton({ onExpand(true) }) {
            Text(if (state.loading) stringResource(Res.string.history_loading) else if (state.error != null) stringResource(Res.string.history_open_error) else state.name.ifBlank { stringResource(Res.string.history_review) }, maxLines = 2)
        } else Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row { Text(state.name.ifBlank { stringResource(Res.string.history_review) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ onExpand(false) }) { Text(stringResource(Res.string.track_collapse)) } }
            state.status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                TextButton(onRetry) { Text(stringResource(Res.string.viewer_retry)) }
            }
            if (!state.loading && state.error == null) {
                ReviewStatistics(state.distance, state.elapsed, state.recording, state.moving, state.interrupted, state.estimated)
                ReviewTelemetry(state, onSeries, onSelect, onEvents, onExport)
            }
        }
    }
}

@Composable
internal fun ReviewTelemetry(state: ReviewState, onSeries: (Int) -> Unit, onSelect: (Long, String?) -> Unit,
    onEvents: (Boolean) -> Unit, onExport: (Boolean) -> Unit) {
    state.actionError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    ReviewSeriesList(state.series, state.selectedSeries, onSeries)
    state.series.getOrNull(state.selectedSeries)?.let { series ->
        Text("${stringResource(series.metric)} · ${series.unit}", style = MaterialTheme.typography.titleSmall)
        Text(series.source, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.history_sample_statistics, series.minimum, series.mean, series.maximum, series.count.toString()), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.history_selected_value, state.selectedValue ?: "—"),
            style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ReviewSelectionLine(stringResource(Res.string.history_selected_time,
            state.selectedTime?.let { Instant.fromEpochMilliseconds(it).toString() } ?: "—"))
        TelemetryChart(series.points, series.unit, state.fromTime, state.toTime, state.selectedTime, onSelect)
    }
    if (!state.heartRateAvailable) Text(stringResource(Res.string.history_heart_unavailable), style = MaterialTheme.typography.bodySmall)
    if (state.series.isEmpty()) Text(stringResource(Res.string.history_no_measurements), style = MaterialTheme.typography.bodySmall)
    ReviewSelectionLine(state.selectedSource.orEmpty())
    ReviewSelectionLine(state.selectedPositionTime?.let {
        stringResource(Res.string.history_position_time, Instant.fromEpochMilliseconds(it).toString())
    }.orEmpty())
    ReviewSelectionLine(if (state.selectedTime != null && state.selectedPosition == null)
        stringResource(Res.string.history_no_position) else "")
    Row {
        TextButton({ onEvents(true) }) { Text(stringResource(Res.string.history_events)) }
        TextButton({ onExport(true) }, enabled = state.exportable && !state.busy) { Text(stringResource(Res.string.history_export)) }
    }
}

@Composable
private fun ReviewSelectionLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun ReviewStatistics(distance: String, elapsed: String, recording: String, moving: String, interrupted: String, estimated: Boolean) {
    Column {
        Text(stringResource(Res.string.track_distance, distance))
        Text(stringResource(Res.string.track_elapsed, elapsed), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.track_recording_time, recording), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.track_moving_time, moving), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.history_interrupted_time, interrupted), style = MaterialTheme.typography.bodySmall)
        if (estimated) Text(stringResource(Res.string.history_estimated_time), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun ReviewSeriesList(series: ImmutableList<ReviewSeries>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        series.forEachIndexed { index, item -> ReviewSeriesItem(index, item.metric, item.source, selected == index, onSelect) }
    }
}
@Composable
private fun ReviewSeriesItem(index: Int, metric: org.jetbrains.compose.resources.StringResource, source: String, selected: Boolean, onSelect: (Int) -> Unit) {
    FilterChip(selected, { onSelect(index) }, label = { Text("${stringResource(metric)} · $source", maxLines = 1) })
}

@Composable
internal fun ReviewDialogs(state: ReviewState, onSelect: (Long, String?) -> Unit, onEvents: (Boolean) -> Unit, onExport: (Int) -> Unit, onCloseExport: () -> Unit) {
    if (state.eventList) AlertDialog(onDismissRequest = { onEvents(false) }, title = { Text(stringResource(Res.string.history_events)) },
        text = { ReviewEventList(state.events) { time, id -> onSelect(time, id); onEvents(false) } },
        confirmButton = { TextButton({ onEvents(false) }) { Text(stringResource(Res.string.viewer_done)) } })
    if (state.exporting) ActivityExportDialog(false, state.busy, state.actionError ?: state.error, onExport, onCloseExport)
}
@Composable
private fun ReviewEventList(events: ImmutableList<ReviewEvent>, onSelect: (Long, String?) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 400.dp)) { items(events, key = { it.id }) { ReviewEventItem(it, onSelect) } }
}
@Composable
private fun ReviewEventItem(event: ReviewEvent, onSelect: (Long, String?) -> Unit) {
    TextButton({ onSelect(event.time, event.relatedSampleId) }) { Column {
        Text(stringResource(event.label))
        Text(Instant.fromEpochMilliseconds(event.time).toString(), style = MaterialTheme.typography.bodySmall)
    } }
}
