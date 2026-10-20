/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bes.max.bmaps.feature.viewer.history.presentation.ChartPoint
import bes.max.bmaps.feature.viewer.history.presentation.reviewNumber
import bmaps.feature.viewer.generated.resources.*
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Instant

@Composable
internal fun TelemetryChart(points: ImmutableList<ChartPoint>, unit: String, from: Long, to: Long,
    selected: Long?, onSelect: (Long, String?) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val cursor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = stringResource(Res.string.history_chart_description)
    val selection = selected?.let { Instant.fromEpochMilliseconds(it).toString() }.orEmpty()
    val select by rememberUpdatedState(onSelect)
    val duration = (to - from).coerceAtLeast(1)
    val minimum = points.minOfOrNull { it.value } ?: 0.0
    val maximum = points.maxOfOrNull { it.value } ?: 1.0
    val valueRange = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
    fun fraction(time: Long) = ((time - from).toDouble() / duration).toFloat().coerceIn(0f, 1f)
    fun selectFraction(value: Float): Boolean {
        val time = from + (duration * value.coerceIn(0f, 1f)).toLong()
        val point = points.minByOrNull { abs(it.time - time) } ?: return false
        select(point.time, point.id)
        return true
    }
    Text(stringResource(Res.string.history_chart_hint), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("${reviewNumber(maximum)} $unit", style = MaterialTheme.typography.labelSmall)
            Text("${reviewNumber(minimum)} $unit", style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {
                contentDescription = label
                stateDescription = selection
                if (points.isNotEmpty()) {
                    progressBarRangeInfo = ProgressBarRangeInfo(selected?.let(::fraction) ?: 0f, 0f..1f)
                    setProgress { selectFraction(it) }
                }
            }.pointerInput(points, from, to) {
                detectTapGestures { selectFraction(it.x / size.width.coerceAtLeast(1)) }
            }.pointerInput(points, from, to) {
                detectHorizontalDragGestures(
                    onDragStart = { selectFraction(it.x / size.width.coerceAtLeast(1)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        selectFraction(change.position.x / size.width.coerceAtLeast(1))
                    },
                )
            }) {
                fun position(point: ChartPoint) = Offset(fraction(point.time) * size.width,
                    (size.height - (point.value - minimum) / valueRange * size.height).toFloat())
                drawLine(grid, Offset.Zero, Offset(size.width, 0f))
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
                points.zipWithNext().forEach { (a, b) ->
                    if (a.run == b.run) drawLine(color, position(a), position(b), 2.dp.toPx())
                }
                points.forEach { drawCircle(color, 2.dp.toPx(), position(it)) }
                selected?.takeIf { it in from..to }?.let { time ->
                    val x = fraction(time) * size.width
                    drawLine(cursor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    points.firstOrNull { it.time == time }?.let { drawCircle(cursor, 4.dp.toPx(), position(it)) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(chartTime(from), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(chartTime(to), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
        }
    }
}

private fun chartTime(time: Long): String {
    val instant = Instant.fromEpochMilliseconds(time).toString()
    return instant.substringBefore('T') + "\n" + instant.substringAfter('T').substringBefore('.').removeSuffix("Z") + " UTC"
}
