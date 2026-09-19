package bes.max.bmaps.core.mapengine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
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
    markerContent: @Composable (MapMarker) -> Unit = {},
) {
    val state by renderer.state.collectAsStateWithLifecycle()
    val clicked by rememberUpdatedState(onOverlayClick)
    val marker by rememberUpdatedState(markerContent)
    val engine = state.engine
    DisposableEffect(engine, markers, paths) {
        if (engine != null) {
            markers.forEach { item ->
                engine.addMarker(item.id, item.position.x, item.position.y) { marker(item) }
            }
            paths.forEach { item ->
                engine.addPath(item.id, color = item.color, width = 3.dp,
                    fillColor = if (item.filled) item.color.copy(alpha = 0.22f) else null,
                    clickable = true, simplify = 0f) {
                    item.points.forEach { addPoint(it.x, it.y) }
                }
            }
            engine.onMarkerClick { id, x, y -> clicked(id, MapPoint(x, y)) }
            engine.onPathClick { id, x, y -> clicked(id, MapPoint(x, y)) }
        }
        onDispose {
            markers.forEach { engine?.removeMarker(it.id) }
            paths.forEach { engine?.removePath(it.id) }
        }
    }
    Box(modifier.onSizeChanged(renderer::resize)) {
        state.engine?.let { MapUI(Modifier.fillMaxSize(), it) }
    }
}
