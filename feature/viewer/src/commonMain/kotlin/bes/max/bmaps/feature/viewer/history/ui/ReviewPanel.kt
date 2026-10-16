/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.history.presentation.*
import bmaps.feature.viewer.generated.resources.*
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
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
        TelemetryChart(series.points, state.fromTime, state.toTime, state.selectedTime, onSelect)
        if (series.points.isNotEmpty()) Slider(
            value = series.points.indexOfFirst { it.time == state.selectedTime }.coerceAtLeast(0).toFloat(),
            onValueChange = { value -> series.points.getOrNull(value.toInt())?.let { onSelect(it.time, it.id) } },
            valueRange = 0f..(series.points.size - 1).coerceAtLeast(1).toFloat(),
            modifier = Modifier.semantics { contentDescription = "${series.unit}: ${series.source}" })
    }
    if (!state.heartRateAvailable) Text(stringResource(Res.string.history_heart_unavailable), style = MaterialTheme.typography.bodySmall)
    if (state.series.isEmpty()) Text(stringResource(Res.string.history_no_measurements), style = MaterialTheme.typography.bodySmall)
    state.selectedValue?.let { Text(stringResource(Res.string.history_selected_value, it), style = MaterialTheme.typography.bodyMedium) }
    state.selectedSource?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    state.selectedTime?.let { Text(stringResource(Res.string.history_selected_time, Instant.fromEpochMilliseconds(it).toString()), style = MaterialTheme.typography.bodySmall) }
    state.selectedPositionTime?.let { Text(stringResource(Res.string.history_position_time, Instant.fromEpochMilliseconds(it).toString()), style = MaterialTheme.typography.bodySmall) }
    if (state.selectedTime != null && state.selectedPosition == null) Text(stringResource(Res.string.history_no_position), style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton({ onEvents(true) }) { Text(stringResource(Res.string.history_events)) }
        TextButton({ onExport(true) }, enabled = state.exportable && !state.busy) { Text(stringResource(Res.string.history_export)) }
    }
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
private fun TelemetryChart(points: ImmutableList<ChartPoint>, from: Long, to: Long, selected: Long?, onSelect: (Long, String?) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val cursor = MaterialTheme.colorScheme.tertiary
    val label = stringResource(Res.string.history_chart_description)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = label }
        .pointerInput(points, from, to) { detectTapGestures { position ->
            val time = from + ((to - from) * (position.x / size.width).coerceIn(0f, 1f)).toLong()
            points.minByOrNull { abs(it.time - time) }?.let { onSelect(it.time, it.id) }
        } }) {
        val minimum = points.minOfOrNull { it.value } ?: 0.0
        val maximum = points.maxOfOrNull { it.value } ?: 1.0
        fun position(point: ChartPoint) = Offset(((point.time - from).toDouble() / (to - from).coerceAtLeast(1) * size.width).toFloat(),
            (size.height - (point.value - minimum) / (maximum - minimum).coerceAtLeast(1.0) * size.height).toFloat())
        points.zipWithNext().forEach { (a, b) -> if (a.run == b.run) drawLine(color, position(a), position(b), 2.dp.toPx()) }
        points.forEach { drawCircle(color, 2.dp.toPx(), position(it)) }
        selected?.let { val x = ((it - from).toDouble() / (to - from).coerceAtLeast(1) * size.width).toFloat(); drawLine(cursor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx()) }
    }
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
