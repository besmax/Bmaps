package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import kotlin.math.ceil
import kotlin.math.floor

internal class PackageTileCoverage(bounds: BoundingBox, range: ZoomRange, levels: Set<Int>) {
    private data class Rectangle(val zoom: Int, val columns: LongRange, val rows: LongRange)
    private val rectangles: List<Rectangle>
    val count: Long

    init {
        require(range.min in 0..52 && range.max in range.min..52)
        val selected = levels.ifEmpty { (range.min..range.max).toSet() }.sorted()
        require(selected.isNotEmpty() && selected.all { it in range.min..range.max })
        val regions = requireNotNull(WebMercator.splitBounds(bounds))
        val north = requireNotNull(WebMercator.normalized(GeographicCoordinate(bounds.north, 0.0))).y
        val south = requireNotNull(WebMercator.normalized(GeographicCoordinate(bounds.south, 0.0))).y
        rectangles = selected.flatMap { zoom ->
            val side = 1L shl zoom
            val rowRange = floor(north * side).toLong() until ceil(south * side).toLong()
            val intervals = regions.map {
                floor((it.west + 180) / 360 * side).toLong() until ceil((it.east + 180) / 360 * side).toLong()
            }.sortedBy { it.first }
            val merged = mutableListOf<LongRange>()
            for (interval in intervals) {
                if (interval.isEmpty()) continue
                val last = merged.lastOrNull()
                if (last != null && interval.first <= last.last + 1) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, interval.last)
                } else merged.add(interval)
            }
            merged.map { Rectangle(zoom, it, rowRange) }
        }
        count = rectangles.fold(0L) { sum, rectangle ->
            val columns = rectangle.columns.last - rectangle.columns.first + 1
            val rows = rectangle.rows.last - rectangle.rows.first + 1
            require(columns > 0 && rows > 0 && rows <= (Long.MAX_VALUE - sum) / columns)
            sum + columns * rows
        }
        require(count > 0)
    }

    fun contains(key: TileKey): Boolean = rectangles.any {
        key.level == it.zoom && key.column in it.columns && key.row in it.rows
    }

    fun tiles(): Sequence<TileKey> = sequence {
        for (rectangle in rectangles) for (column in rectangle.columns) for (row in rectangle.rows) {
            yield(TileKey(rectangle.zoom, column, row))
        }
    }
}
