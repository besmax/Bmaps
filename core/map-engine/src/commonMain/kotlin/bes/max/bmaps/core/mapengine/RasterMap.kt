package bes.max.bmaps.core.mapengine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import ovh.plrapps.mapcompose.api.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.plrapps.mapcompose.ui.MapUI

@Composable
fun RasterMap(
    renderer: RasterMapRenderer,
    modifier: Modifier = Modifier,
    markers: List<MapMarker> = emptyList(),
    paths: List<MapPath> = emptyList(),
    onOverlayClick: (String, MapPoint) -> Unit = { _, _ -> },
    onGestureStart: () -> Unit = {},
    markerContent: @Composable (MapMarker) -> Unit = {},
) {
    val state by renderer.state.collectAsStateWithLifecycle()
    val clicked by rememberUpdatedState(onOverlayClick)
    val marker by rememberUpdatedState(markerContent)
    val gesture by rememberUpdatedState(onGestureStart)
    val engine = state.engine
    val markerRegistry = remember(engine) {
        OverlayRegistry<MapMarker>(MapMarker::id, { item ->
                engine?.addMarker(
                    item.id, item.position.x, item.position.y,
                    relativeOffset = if (item.anchor == MapMarkerAnchor.CENTER) Offset(-0.5f, -0.5f) else Offset(-0.5f, -1f),
                    zIndex = item.zIndex,
                ) { marker(item) }
        }, { engine?.removeMarker(it) })
    }
    val pathRegistry = remember(engine) {
        OverlayRegistry<MapPath>(MapPath::id, { item ->
                engine?.addPath(item.id, color = item.color, width = 3.dp, zIndex = item.zIndex,
                    fillColor = if (item.filled) item.color.copy(alpha = 0.22f) else null,
                    clickable = true, simplify = 0f) {
                    item.points.forEach { addPoint(it.x, it.y) }
                }
        }, { engine?.removePath(it) })
    }
    SideEffect { markerRegistry.sync(markers); pathRegistry.sync(paths) }
    DisposableEffect(engine) {
        if (engine != null) {
            engine.onMarkerClick { id, x, y -> clicked(id, MapPoint(x, y)) }
            engine.onPathClick { id, x, y -> clicked(id, MapPoint(x, y)) }
        }
        onDispose {
            markerRegistry.clear()
            pathRegistry.clear()
            engine?.onMarkerClick { _, _, _ -> }
            engine?.onPathClick { _, _, _ -> }
        }
    }
    Box(modifier.onSizeChanged(renderer::resize).pointerInput(renderer) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            renderer.controller.cancelMove()
            gesture()
            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
        }
    }) {
        state.engine?.let { MapUI(Modifier.fillMaxSize(), it) }
    }
}
