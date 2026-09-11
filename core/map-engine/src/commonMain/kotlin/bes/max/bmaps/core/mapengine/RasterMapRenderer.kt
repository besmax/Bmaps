package bes.max.bmaps.core.mapengine

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.api.*
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.max
import kotlin.math.min

internal data class RasterRendererState(val engine: MapState? = null)
data class RasterRendererEvent(val generation: Int, val event: MapEvent)

class RasterMapRenderer {
    private data class Content(val generation: Int, val config: RasterMapConfig, val layers: List<RasterLayer>)
    private val content = MutableStateFlow<Content?>(null)
    private val size = MutableStateFlow(IntSize.Zero)
    private val mutableState = MutableStateFlow(RasterRendererState())
    internal val state = mutableState.asStateFlow()
    private val eventChannel = Channel<RasterRendererEvent>(64, BufferOverflow.DROP_OLDEST)
    val events = eventChannel.receiveAsFlow()
    val controller = RasterMapController()
    private val runner = Mutex()
    private var retainedContent: Content? = null
    private var retainedViewport = MapViewport()

    fun setContent(generation: Int, config: RasterMapConfig, layers: List<RasterLayer>) {
        require(layers.isNotEmpty() && layers.map { it.id }.distinct().size == layers.size)
        require(layers.first().opacity == 1f) { "The base layer must be opaque" }
        content.value = Content(generation, config, layers)
    }

    fun clearContent() { content.value = null }
    internal fun resize(value: IntSize) {
        if (value.width > 0 && value.height > 0) size.value = value
    }

    suspend fun run() = runner.withLock {
        combine(content, size) { content, size -> content to size }.collectLatest { (content, size) ->
            if (content != null && size.width > 0 && size.height > 0) {
                if (retainedContent != content) {
                    retainedContent = content
                    retainedViewport = content.config.initialViewport
                }
                renderSession(content, size)
            }
        }
    }

    private suspend fun renderSession(content: Content, size: IntSize): Unit = coroutineScope {
        val config = content.config
        val layers = content.layers
        fun emit(event: MapEvent) { eventChannel.trySend(RasterRendererEvent(content.generation, event)) }
        val dimensions = config.pyramid.engineSize()
        if (dimensions == null) {
            emit(MapEvent.Unavailable(MapUnavailableReason.PYRAMID_DIMENSIONS))
            return@coroutineScope
        }
        val (width, height) = dimensions
        val fit = min(size.width.toDouble() / width, size.height.toDouble() / height)
        val safeMax = (Int.MAX_VALUE.toDouble() - max(size.width, size.height)) / max(width, height)
        val requestedMax =
            config.maxScale?.times(fit) ?: min(max(2.0, config.minScale * fit), safeMax)
        if (requestedMax > safeMax || retainedViewport.scale * fit > safeMax || config.minScale * fit > requestedMax) {
            emit(MapEvent.Unavailable(MapUnavailableReason.VIEWPORT_PRECISION))
            return@coroutineScope
        }
        var state: MapState? = null
        var tiles: RasterTileSession? = null
        try {
            tiles = RasterTileSession.open(config.pyramid, layers, { emit(it) })
            val session = tiles
            state = MapState(
                config.pyramid.levels.max - config.pyramid.levels.min + 1,
                width, height, config.pyramid.tileSize, workerCount = 8
            ) {
                minimumScaleMode(Forced(config.minScale * fit))
                maxScale(requestedMax)
                scale(retainedViewport.scale * fit)
                scroll(retainedViewport.center.x, retainedViewport.center.y)
                preloadingPadding(0)
            }
            val map = state
            map.disableRotation()
            if (!config.interactions.pan) map.disableScrolling()
            if (!config.interactions.zoom) map.disableZooming()
            map.onTap { x, y -> emit(MapEvent.Tap(MapPoint(x, y))) }
            map.setStateChangeListener {
                val center = MapPoint(centroidX, centroidY)
                if (center.isValid && scale.isFinite() && scale > 0) {
                    retainedViewport = MapViewport(center, scale / fit)
                    emit(
                        MapEvent.ViewportChanged(
                            retainedViewport, MapWindow(
                                center.x - size.width / (2.0 * width * scale),
                                center.y - size.height / (2.0 * height * scale),
                                center.x + size.width / (2.0 * width * scale),
                                center.y + size.height / (2.0 * height * scale)
                            )
                        )
                    )
                }
            }
            layers.forEach { layer ->
                map.addLayer(
                    { row, col, level -> session.stream(layer.id, row, col, level) },
                    layer.opacity
                )
            }
            mutableState.value = RasterRendererState(map)
            controller.let { controls ->
                launch {
                    controls.zooms.collectLatest { factor ->
                        map.scrollTo(
                            map.centroidX, map.centroidY,
                            destScale = (map.scale * factor).coerceIn(
                                config.minScale * fit,
                                requestedMax
                            )
                        )
                    }
                }
            }
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(MapEvent.Unavailable(MapUnavailableReason.INITIALIZATION))
        } finally {
            mutableState.value = RasterRendererState()
            withContext(NonCancellable) {
                try {
                    state?.shutdownAndJoin()
                } finally {
                    tiles?.close()
                }
            }
        }
    }
}

// MapComposeMP 1.1.3 closes its dispatcher without joining the jobs that still use it.
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private suspend fun MapState.shutdownAndJoin() {
    scope.coroutineContext.job.cancelAndJoin()
    shutdown()
}
