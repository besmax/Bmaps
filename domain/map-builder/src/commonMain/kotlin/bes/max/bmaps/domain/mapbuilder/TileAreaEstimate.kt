package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import kotlin.math.ceil
import kotlin.math.floor

object TileAreaEstimate {
    fun estimate(bounds: BoundingBox, levels: Set<Int>, averageTileBytes: Long): BuildEstimate {
        val regions = checkNotNull(WebMercator.splitBounds(bounds))
        var count = 0L
        for (level in levels) {
            val side = 1L shl level
            val north = checkNotNull(WebMercator.normalized(GeographicCoordinate(bounds.north, 0.0))).y
            val south = checkNotNull(WebMercator.normalized(GeographicCoordinate(bounds.south, 0.0))).y
            val rows = ceil(south * side).toLong() - floor(north * side).toLong()
            val columns = regions.sumOf { region ->
                ceil((region.east + 180) / 360 * side).toLong() - floor((region.west + 180) / 360 * side).toLong()
            }.coerceAtMost(side)
            if (columns != 0L && rows > (Long.MAX_VALUE - count) / columns) return BuildEstimate(Long.MAX_VALUE, null)
            count += columns * rows
        }
        val perTile = averageTileBytes + 256L
        val bytes = if (count > (Long.MAX_VALUE - 4096L) / perTile) null else count * perTile + 4096L
        return BuildEstimate(count, bytes)
    }
}
