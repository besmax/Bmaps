package bes.max.bmaps.feature.viewer

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.mapbuilder.PackageLayer
import kotlin.math.ceil
import kotlin.math.floor

internal fun offlineAutomaticPyramid(layer: PackageLayer, region: Int = 0): TilePyramid? {
    val levels = layer.zoomLevels.ifEmpty { (layer.zoomRange.min..layer.zoomRange.max).toSet() }.sorted()
    if (levels.isEmpty() || levels.zipWithNext().any { (a, b) -> b != a + 1 }) return null
    return offlinePyramid(layer, levels.first(), region)?.copy(levels = ZoomRange(levels.first(), levels.last()))
        ?.takeIf { it.engineSize() != null }
}

internal fun offlinePyramid(layer: PackageLayer, level: Int, region: Int = 0): TilePyramid? {
    if (level !in 0..52 || level !in layer.zoomRange.min..layer.zoomRange.max ||
        (layer.zoomLevels.isNotEmpty() && level !in layer.zoomLevels) ||
        layer.coordinateSystem != CoordinateSystemId.WebMercator || layer.tileWidth != layer.tileHeight) return null
    val bounds = WebMercator.splitBounds(layer.bounds)?.getOrNull(region) ?: return null
    val north = WebMercator.normalized(GeographicCoordinate(bounds.north, bounds.west)) ?: return null
    val south = WebMercator.normalized(GeographicCoordinate(bounds.south, bounds.east)) ?: return null
    val side = 1L shl level
    val left = floor(north.x * side).toLong()
    val right = ceil(south.x * side).toLong()
    val top = floor(north.y * side).toLong()
    val bottom = ceil(south.y * side).toLong()
    if (right - left !in 1..Int.MAX_VALUE.toLong() || bottom - top !in 1..Int.MAX_VALUE.toLong()) return null
    return TilePyramid(ZoomRange(level, level), left, top, (right - left).toInt(), (bottom - top).toInt(), layer.tileWidth)
        .takeIf { it.engineSize() != null }
}

internal fun offlineInitialViewport(layer: PackageLayer, region: Int, pyramid: TilePyramid, minimumScale: Double): MapViewport {
    val bounds = requireNotNull(WebMercator.splitBounds(layer.bounds))[region]
    val northWest = requireNotNull(WebMercator.normalized(GeographicCoordinate(bounds.north, bounds.west)))
    val southEast = requireNotNull(WebMercator.normalized(GeographicCoordinate(bounds.south, bounds.east)))
    val side = (1L shl pyramid.levels.min).toDouble()
    val center = MapPoint(((northWest.x + southEast.x) / 2 * side - pyramid.originColumn) / pyramid.columns,
        ((northWest.y + southEast.y) / 2 * side - pyramid.originRow) / pyramid.rows)
    val extent = maxOf((southEast.x - northWest.x) * side / pyramid.columns,
        (southEast.y - northWest.y) * side / pyramid.rows)
    val detailScale = maxOf(1.0, (1L shl (pyramid.levels.max - pyramid.levels.min)) * maxOf(pyramid.columns, pyramid.rows) / 4.0)
    return MapViewport(center, (1.0 / extent).coerceIn(minimumScale, maxOf(minimumScale, detailScale)))
}

internal fun alignedLayers(layers: List<PackageLayer>): Boolean {
    val root = layers.firstOrNull() ?: return false
    fun PackageLayer.levels() = zoomLevels.ifEmpty { (zoomRange.min..zoomRange.max).toSet() }
    return layers.all {
        it.content.kind == TileContentKind.RASTER && it.content.rasterFormats.isNotEmpty() &&
            it.bounds == root.bounds && it.coordinateSystem == CoordinateSystemId.WebMercator &&
            it.tileWidth > 0 && it.tileWidth == root.tileWidth && it.tileHeight == root.tileWidth &&
            it.zoomRange == root.zoomRange && it.levels() == root.levels() &&
            it.opacity.isFinite() && it.opacity in 0.0..1.0
    }
}
