package bes.max.bmaps.feature.constructor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SelectionFrame(frame: SelectionRectangle, onDrag: (SelectionHandle, Float, Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val left = maxWidth * frame.left
        val top = maxHeight * frame.top
        val frameWidth = maxWidth * (frame.right - frame.left)
        val frameHeight = maxHeight * (frame.bottom - frame.top)
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * frame.left
            val y = size.height * frame.top
            val right = size.width * frame.right
            val bottom = size.height * frame.bottom
            val shade = Color.Black.copy(alpha = 0.25f)
            drawRect(shade, size = Size(size.width, y))
            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(shade, Offset(0f, y), Size(x, bottom - y))
            drawRect(shade, Offset(right, y), Size(size.width - right, bottom - y))
            drawRect(Color.White, Offset(x, y), Size(right - x, bottom - y), style = Stroke(3.dp.toPx()))
        }
        SelectionDragTarget(SelectionHandle.MOVE, width, height, onDrag,
            Modifier.offset(left, top).size(frameWidth, frameHeight))
        val corners = listOf(
            Triple(SelectionHandle.TOP_LEFT, left, top),
            Triple(SelectionHandle.TOP_RIGHT, left + frameWidth, top),
            Triple(SelectionHandle.BOTTOM_LEFT, left, top + frameHeight),
            Triple(SelectionHandle.BOTTOM_RIGHT, left + frameWidth, top + frameHeight),
        )
        corners.forEach { (handle, x, y) ->
            SelectionDragTarget(handle, width, height, onDrag,
                Modifier.offset(x - 24.dp, y - 24.dp).size(48.dp))
        }
        Text(stringResource(Res.string.selection_instructions), color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 76.dp, start = 16.dp, end = 16.dp))
    }
}

@Composable
private fun SelectionDragTarget(
    handle: SelectionHandle,
    width: Float,
    height: Float,
    onDrag: (SelectionHandle, Float, Float) -> Unit,
    modifier: Modifier,
) {
    val description = stringResource(when (handle) {
        SelectionHandle.MOVE -> Res.string.move_selected_area
        SelectionHandle.TOP_LEFT -> Res.string.resize_top_left
        SelectionHandle.TOP_RIGHT -> Res.string.resize_top_right
        SelectionHandle.BOTTOM_LEFT -> Res.string.resize_bottom_left
        SelectionHandle.BOTTOM_RIGHT -> Res.string.resize_bottom_right
    })
    val moveLeft = stringResource(Res.string.adjust_left)
    val moveRight = stringResource(Res.string.adjust_right)
    val moveUp = stringResource(Res.string.adjust_up)
    val moveDown = stringResource(Res.string.adjust_down)
    Canvas(modifier.testTag("selection-${handle.name.lowercase()}").semantics {
        contentDescription = description
        customActions = listOf(
            CustomAccessibilityAction(moveLeft) { onDrag(handle, -0.05f, 0f); true },
            CustomAccessibilityAction(moveRight) { onDrag(handle, 0.05f, 0f); true },
            CustomAccessibilityAction(moveUp) { onDrag(handle, 0f, -0.05f); true },
            CustomAccessibilityAction(moveDown) { onDrag(handle, 0f, 0.05f); true },
        )
    }.pointerInput(handle, width, height, onDrag) {
        detectDragGestures { change, amount ->
            change.consume()
            if (width > 0 && height > 0) onDrag(handle, amount.x / width, amount.y / height)
        }
    }) {
        if (handle != SelectionHandle.MOVE) {
            drawCircle(Color.White, 8.dp.toPx())
            drawCircle(Color.DarkGray, 8.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
    }
}
