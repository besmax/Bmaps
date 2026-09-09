package bes.max.bmaps.core.mapengine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import ovh.plrapps.mapcompose.api.*
import ovh.plrapps.mapcompose.ui.MapUI
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.max
import kotlin.math.min

@Composable
fun RasterMap(config: RasterMapConfig, layers: List<RasterLayer>, modifier: Modifier = Modifier,
              onEvent: (MapEvent) -> Unit = {}) {
    require(layers.isNotEmpty() && layers.map { it.id }.distinct().size == layers.size)
    require(layers.first().opacity == 1f) { "The base layer must be opaque" }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var engine by remember { mutableStateOf<MapState?>(null) }
    val callback by rememberUpdatedState(onEvent)
    val events = remember { Channel<MapEvent>(64, BufferOverflow.DROP_OLDEST) }
    LaunchedEffect(events) { for (event in events) callback(event) }
    DisposableEffect(events) { onDispose { events.close() } }
    LaunchedEffect(config, layers, size) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val dimensions = config.pyramid.engineSize()
        if (dimensions == null) {
            events.trySend(MapEvent.Unavailable("Tile pyramid exceeds renderer dimensions; select a smaller regional window"))
            return@LaunchedEffect
        }
        val (width, height) = dimensions
        val fit = min(size.width.toDouble() / width, size.height.toDouble() / height)
        val safeMax = (Int.MAX_VALUE.toDouble() - max(size.width, size.height)) / max(width, height)
        val requestedMax = config.maxScale?.times(fit) ?: min(max(2.0, config.minScale * fit), safeMax)
        if (requestedMax > safeMax || config.initialViewport.scale * fit > safeMax || config.minScale * fit > requestedMax) {
            events.trySend(MapEvent.Unavailable("Viewport scale exceeds renderer precision"))
            return@LaunchedEffect
        }
        var state: MapState? = null
        var tiles: RasterTileSession? = null
        try {
            tiles = RasterTileSession.open(config.pyramid, layers, { events.trySend(it) })
            val session = tiles
            state = MapState(config.pyramid.levels.max - config.pyramid.levels.min + 1,
                width, height, config.pyramid.tileSize, workerCount = 2) {
                minimumScaleMode(Forced(config.minScale * fit))
                maxScale(requestedMax)
                scale(config.initialViewport.scale * fit)
                scroll(config.initialViewport.center.x, config.initialViewport.center.y)
                preloadingPadding(0)
            }
            val map = state
            map.disableRotation()
            if (!config.interactions.pan) map.disableScrolling()
            if (!config.interactions.zoom) map.disableZooming()
            map.onTap { x, y -> events.trySend(MapEvent.Tap(MapPoint(x, y))) }
            map.setStateChangeListener {
                events.trySend(MapEvent.ViewportChanged(MapViewport(MapPoint(centroidX, centroidY), scale / fit)))
            }
            layers.forEach { layer ->
                map.addLayer({ row, col, level -> session.stream(layer.id, row, col, level) }, layer.opacity)
            }
            engine = map
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { events.trySend(MapEvent.Unavailable("Map initialization failed")) }
        finally {
            engine = null
            withContext(NonCancellable) {
                try { state?.shutdownAndJoin() }
                finally { tiles?.close() }
            }
        }
    }
    Box(modifier.onSizeChanged { size = it }) {
        engine?.let { MapUI(Modifier.fillMaxSize(), it) }
    }
}

// MapComposeMP 1.1.3 closes its dispatcher without joining the jobs that still use it.
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private suspend fun MapState.shutdownAndJoin() {
    scope.coroutineContext.job.cancelAndJoin()
    shutdown()
}
