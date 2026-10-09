/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor.selection.ui

import bes.max.bmaps.feature.constructor.selection.presentation.AreaSelectionState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SelectionFrame(
    selection: AreaSelectionState,
    onStart: (Float, Float) -> Unit,
    onPoint: (Float, Float) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val start = rememberUpdatedState(onStart)
    val point = rememberUpdatedState(onPoint)
    val finish = rememberUpdatedState(onFinish)
    val cancel = rememberUpdatedState(onCancel)
    val amber = MaterialTheme.colorScheme.primaryContainer
    val instructions = stringResource(Res.string.selection_instructions)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().testTag("selection-drawing").semantics {
            contentDescription = instructions
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                if (size.width > 0 && size.height > 0) {
                    var completed = false
                    try {
                        start.value(down.position.x / size.width, down.position.y / size.height)
                        do {
                            val event = awaitPointerEvent()
                            val active = event.changes.firstOrNull { it.id == down.id }
                            event.changes.forEach { it.consume() }
                            if (active == null || event.changes.any { it.id != down.id && it.pressed }) break
                            active.historical.forEach {
                                point.value(it.position.x / size.width, it.position.y / size.height)
                            }
                            point.value(active.position.x / size.width, active.position.y / size.height)
                            if (!active.pressed) {
                                finish.value()
                                completed = true
                                break
                            }
                        } while (true)
                    } finally {
                        if (!completed) cancel.value()
                    }
                }
            }
        }) {
            val frame = selection.frame
            if (frame != null && !selection.drawing) {
                val x = size.width * frame.left
                val y = size.height * frame.top
                val right = size.width * frame.right
                val bottom = size.height * frame.bottom
                val shade = Color.Black.copy(alpha = 0.25f)
                drawRect(shade, size = Size(size.width, y))
                drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(shade, Offset(0f, y), Size(x, bottom - y))
                drawRect(shade, Offset(right, y), Size(size.width - right, bottom - y))
                drawRect(amber.copy(alpha = 0.12f), Offset(x, y), Size(right - x, bottom - y))
                drawRect(amber, Offset(x, y), Size(right - x, bottom - y), style = Stroke(2.dp.toPx()))
            }
            if (selection.points.isNotEmpty()) {
                val path = Path()
                selection.points.forEachIndexed { index, point ->
                    val x = point.x.toFloat() * size.width
                    val y = point.y.toFloat() * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, amber, style = Stroke(3.dp.toPx()))
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                .padding(top = 76.dp, start = 16.dp, end = 16.dp).widthIn(max = 360.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        ) {
            Text(instructions, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(12.dp))
        }
    }
}
