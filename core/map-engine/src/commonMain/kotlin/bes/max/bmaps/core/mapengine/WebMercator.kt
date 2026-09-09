package bes.max.bmaps.core.mapengine

import kotlin.math.*

object WebMercator : CoordinateTransformer {
    const val MAX_LATITUDE = 85.0511287798066
    const val EARTH_RADIUS = 6_378_137.0
    val extent: Double = PI * EARTH_RADIUS

    override fun supports(source: CoordinateSystemId, target: CoordinateSystemId): Boolean =
        source in systems && target in systems

    override fun transform(coordinate: ProjectedCoordinate, target: CoordinateSystemId): TransformResult {
        if (!supports(coordinate.coordinateSystem, target)) return TransformResult.UnsupportedCoordinateSystem
        val (x, y) = coordinate
        if (!x.isFinite() || !y.isFinite()) return TransformResult.OutsideCoverage
        val geographic = if (coordinate.coordinateSystem == CoordinateSystemId.Wgs84) {
            if (x !in -180.0..180.0 || y !in -MAX_LATITUDE..MAX_LATITUDE) return TransformResult.OutsideCoverage
            GeographicCoordinate(y, x)
        } else {
            if (abs(x) > extent || abs(y) > extent) return TransformResult.OutsideCoverage
            GeographicCoordinate(atan(sinh(y / EARTH_RADIUS)) * 180 / PI, x / extent * 180)
        }
        val result = if (target == CoordinateSystemId.Wgs84) {
            ProjectedCoordinate(geographic.longitude, geographic.latitude, target)
        } else if (coordinate.coordinateSystem == target) coordinate else {
            ProjectedCoordinate(geographic.longitude / 180 * extent,
                when (geographic.latitude) {
                    MAX_LATITUDE -> extent
                    -MAX_LATITUDE -> -extent
                    else -> asinh(tan(geographic.latitude * PI / 180)) * EARTH_RADIUS
                }, target)
        }
        return TransformResult.Success(result)
    }

    fun normalized(coordinate: GeographicCoordinate): MapPoint? {
        if (!coordinate.latitude.isFinite() || !coordinate.longitude.isFinite() ||
            coordinate.latitude !in -MAX_LATITUDE..MAX_LATITUDE || coordinate.longitude !in -180.0..180.0) return null
        val y = when (coordinate.latitude) {
            MAX_LATITUDE -> 0.0
            -MAX_LATITUDE -> 1.0
            else -> (1 - asinh(tan(coordinate.latitude * PI / 180)) / PI) / 2
        }
        return MapPoint((coordinate.longitude + 180) / 360, y)
    }

    fun geographic(point: MapPoint): GeographicCoordinate? {
        if (!point.isValid) return null
        return GeographicCoordinate(when (point.y) {
            0.0 -> MAX_LATITUDE
            1.0 -> -MAX_LATITUDE
            else -> atan(sinh(PI * (1 - 2 * point.y))) * 180 / PI
        }, point.x * 360 - 180)
    }

    fun tileAt(coordinate: GeographicCoordinate, level: Int): TileKey? {
        if (level !in 0..52) return null
        val point = normalized(coordinate) ?: return null
        val count = 1L shl level
        return TileKey(level, floor(point.x * count).toLong().coerceAtMost(count - 1),
            floor(point.y * count).toLong().coerceAtMost(count - 1))
    }

    fun tileBounds(key: TileKey): BoundingBox? {
        if (key.level !in 0..52 || !key.isValidXyz()) return null
        val count = (1L shl key.level).toDouble()
        val nw = geographic(MapPoint(key.column / count, key.row / count))!!
        val se = geographic(MapPoint((key.column + 1) / count, (key.row + 1) / count))!!
        return BoundingBox(nw.longitude, se.latitude, se.longitude, nw.latitude)
    }

    fun splitBounds(bounds: BoundingBox): List<BoundingBox>? {
        if (normalized(GeographicCoordinate(bounds.south, bounds.west)) == null ||
            normalized(GeographicCoordinate(bounds.north, bounds.east)) == null || bounds.south >= bounds.north ||
            bounds.west == bounds.east || (bounds.west == 180.0 && bounds.east == -180.0)) return null
        return if (bounds.west < bounds.east) listOf(bounds) else buildList {
            if (bounds.west < 180) add(bounds.copy(east = 180.0))
            if (bounds.east > -180) add(bounds.copy(west = -180.0))
        }
    }

    private val systems = setOf(CoordinateSystemId.Wgs84, CoordinateSystemId.WebMercator)
}

data class MapPoint(val x: Double, val y: Double) {
    val isValid: Boolean get() = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
}

fun TileKey.isValidXyz(): Boolean = level in 0..63 && column >= 0 && row >= 0 &&
    (level == 63 || (column < (1L shl level) && row < (1L shl level)))

fun TilePyramid.positionOf(coordinate: GeographicCoordinate): MapPoint? {
    if (levels.min > 52) return null
    val world = WebMercator.normalized(coordinate) ?: return null
    val count = (1L shl levels.min).toDouble()
    return MapPoint((world.x * count - originColumn) / columns, (world.y * count - originRow) / rows)
        .takeIf { it.isValid }
}

fun TilePyramid.coordinateAt(point: MapPoint): GeographicCoordinate? {
    if (levels.min > 52 || !point.isValid) return null
    val count = (1L shl levels.min).toDouble()
    return WebMercator.geographic(MapPoint((originColumn + point.x * columns) / count,
        (originRow + point.y * rows) / count))
}
