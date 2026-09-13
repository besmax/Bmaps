package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.ZoomRange

object TileAreaEstimate {
    fun estimate(bounds: BoundingBox, levels: Set<Int>, averageTileBytes: Long): BuildEstimate {
        require(averageTileBytes in 0..Long.MAX_VALUE - 256)
        if (levels.isEmpty()) return BuildEstimate(0, METADATA_BYTES)
        val coverage = try { PackageTileCoverage(bounds, ZoomRange(levels.min(), levels.max()), levels) }
        catch (_: IllegalArgumentException) { return BuildEstimate(Long.MAX_VALUE, null) }
        return estimate(coverage.count, averageTileBytes)
    }

    internal fun estimate(count: Long, averageTileBytes: Long = 32_000): BuildEstimate {
        val perTile = averageTileBytes + 256
        return BuildEstimate(count, if (count <= (Long.MAX_VALUE - METADATA_BYTES) / perTile)
            count * perTile + METADATA_BYTES else null)
    }

    private const val METADATA_BYTES = 1_048_576L
}
