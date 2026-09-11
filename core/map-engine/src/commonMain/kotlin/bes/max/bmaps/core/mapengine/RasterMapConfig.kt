package bes.max.bmaps.core.mapengine

import kotlinx.coroutines.flow.receiveAsFlow

/** A rectangular, tile-aligned region at the lowest source level. */
data class TilePyramid(
    val levels: ZoomRange,
    val originColumn: Long = 0,
    val originRow: Long = 0,
    val columns: Int = 1,
    val rows: Int = 1,
    val tileSize: Int = 256,
) {
    init {
        require(levels.min in 0..63 && levels.max in levels.min..63)
        require(columns > 0 && rows > 0 && tileSize in 1..4096)
        require(TileKey(levels.min, originColumn, originRow).isValidXyz())
        val last = if (levels.min == 63) Long.MAX_VALUE else (1L shl levels.min) - 1
        require(originColumn <= last - (columns - 1) && originRow <= last - (rows - 1))
    }

    fun engineSize(): Pair<Int, Int>? {
        val shift = levels.max - levels.min
        if (shift > 30) return null
        val factor = 1L shl shift
        val width = columns.toLong() * tileSize
        val height = rows.toLong() * tileSize
        if (width > Int.MAX_VALUE / factor || height > Int.MAX_VALUE / factor) return null
        return (width * factor).toInt() to (height * factor).toInt()
    }

    fun sourceKey(localLevel: Int, row: Int, column: Int): TileKey? {
        if (engineSize() == null || localLevel !in 0..(levels.max - levels.min) || row < 0 || column < 0) return null
        val factor = 1L shl localLevel
        if (row >= rows * factor || column >= columns * factor) return null
        return TileKey(levels.min + localLevel, originColumn * factor + column, originRow * factor + row)
    }
}

data class MapViewport(val center: MapPoint = MapPoint(0.5, 0.5), val scale: Double = 1.0)
data class MapInteractions(val pan: Boolean = true, val zoom: Boolean = true)
data class RasterMapConfig(
    val pyramid: TilePyramid,
    val initialViewport: MapViewport = MapViewport(),
    val minScale: Double = 1.0,
    val maxScale: Double? = null,
    val interactions: MapInteractions = MapInteractions(),
) {
    init {
        require(initialViewport.center.isValid && initialViewport.scale.isFinite() && initialViewport.scale > 0)
        require(minScale.isFinite() && minScale > 0)
        require(maxScale == null || (maxScale.isFinite() && maxScale >= minScale))
        require(initialViewport.scale >= minScale && (maxScale == null || initialViewport.scale <= maxScale))
    }
}

fun interface TileSourceFactory { suspend fun open(): TileSource }

class RasterLayer(val id: String, val source: TileSourceFactory, val opacity: Float = 1f) {
    init { require(id.isNotBlank() && opacity.isFinite() && opacity in 0f..1f) }
}

sealed interface MapEvent {
    data class TileLoaded(val layerId: String, val key: TileKey) : MapEvent
    data class TileMissing(val layerId: String, val key: TileKey) : MapEvent
    data class ViewportChanged(val viewport: MapViewport, val visibleWindow: MapWindow? = null) : MapEvent
    data class Tap(val position: MapPoint) : MapEvent
    data class TileFailed(val layerId: String, val key: TileKey, val failure: TileReadFailure) : MapEvent
    data class Unavailable(val reason: MapUnavailableReason) : MapEvent
}


data class MapWindow(val left: Double, val top: Double, val right: Double, val bottom: Double)

class RasterMapController {
    private val commands = kotlinx.coroutines.channels.Channel<Double>(kotlinx.coroutines.channels.Channel.CONFLATED)
    internal val zooms = commands.receiveAsFlow()
    fun zoomIn() { commands.trySend(2.0) }
    fun zoomOut() { commands.trySend(0.5) }
}

enum class MapUnavailableReason { PYRAMID_DIMENSIONS, VIEWPORT_PRECISION, INITIALIZATION, SOURCE_CLEANUP }
