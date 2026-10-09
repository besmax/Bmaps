/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.storage.DemMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.floor

internal data class ReliefAxis(
    val cells: IntArray,
    val low: IntArray,
    val high: IntArray,
    val weights: DoubleArray,
    val valid: BooleanArray,
)

internal data class SampledReliefTile(
    val key: TileKey,
    val x: ReliefAxis,
    val y: ReliefAxis,
    val rows: Array<DoubleArray>,
) {
    suspend fun colors(style: ElevationReliefStyle): IntArray {
        val size = x.low.size
        val output = IntArray(size * size)
        if (!x.valid.any { it } || !y.valid.any { it }) return output
        for (row in 0 until size) {
            currentCoroutineContext().ensureActive()
            if (!y.valid[row]) continue
            val top = rows[y.low[row]]
            val bottom = rows[y.high[row]]
            for (column in 0 until size) {
                if (!x.valid[column]) continue
                val height = interpolate(
                    interpolate(top[x.low[column]], top[x.high[column]], x.weights[column]),
                    interpolate(bottom[x.low[column]], bottom[x.high[column]], x.weights[column]), y.weights[row])
                output[row * size + column] = style.color(height)
            }
        }
        return output
    }
}

private fun interpolate(left: Double, right: Double, weight: Double): Double = when {
    weight == 0.0 -> left
    weight == 1.0 -> right
    !left.isFinite() || !right.isFinite() -> Double.NaN
    else -> left * (1 - weight) + right * weight
}

internal class ElevationTileSampler(
    private val metadata: DemMetadata,
    private val readGrid: suspend (IntArray, IntArray) -> DoubleArray,
) {
    private var longitudeKey: Triple<Int, Long, Int>? = null
    private var longitudeAxis: ReliefAxis? = null
    private var cachedColumns = IntArray(0)
    private val cache = linkedMapOf<Int, DoubleArray>()
    private var cacheBytes = 0

    suspend fun sample(key: TileKey, size: Int): SampledReliefTile {
        require(key.level in 0..52 && key.isValidXyz())
        val side = (1L shl key.level).toDouble()
        val latitude = DoubleArray(size) {
            checkNotNull(WebMercator.geographic(MapPoint(0.5, (key.row + (it + 0.5) / size) / side))).latitude
        }
        val axisKey = Triple(key.level, key.column, size)
        val x = if (axisKey == longitudeKey) checkNotNull(longitudeAxis) else {
            val longitude = DoubleArray(size) { (key.column + (it + 0.5) / size) / side * 360 - 180 }
            axis(longitude, metadata.originLongitude, metadata.longitudeStep, metadata.width).also {
                longitudeKey = axisKey; longitudeAxis = it
            }
        }
        val y = axis(latitude, metadata.originLatitude, metadata.latitudeStep, metadata.height)
        if (!x.valid.any { it } || !y.valid.any { it }) return SampledReliefTile(key, x, y, emptyArray())
        if (!cachedColumns.contentEquals(x.cells)) {
            cache.clear(); cacheBytes = 0; cachedColumns = x.cells
        }
        val rows = arrayOfNulls<DoubleArray>(y.cells.size)
        val missing = mutableListOf<Int>()
        y.cells.forEachIndexed { index, cell ->
            val existing = cache.remove(cell)
            if (existing == null) missing.add(index) else { cache[cell] = existing; rows[index] = existing }
        }
        val chunkSize = minOf(1024, 131072 / x.cells.size)
        for (chunk in missing.chunked(chunkSize)) {
            currentCoroutineContext().ensureActive()
            val values = readGrid(x.cells, IntArray(chunk.size) { y.cells[chunk[it]] })
            require(values.size == x.cells.size * chunk.size)
            chunk.forEachIndexed { offset, index ->
                val valuesRow = values.copyOfRange(offset * x.cells.size, (offset + 1) * x.cells.size)
                rows[index] = valuesRow
                cache[y.cells[index]] = valuesRow
                cacheBytes += valuesRow.size * 8
                while (cacheBytes > 4 * 1024 * 1024) {
                    val oldest = cache.keys.first()
                    cacheBytes -= checkNotNull(cache.remove(oldest)).size * 8
                }
            }
        }
        return SampledReliefTile(key, x, y, Array(rows.size) { checkNotNull(rows[it]) })
    }

    private fun axis(values: DoubleArray, origin: Double, step: Double, limit: Int): ReliefAxis {
        val offset = if (metadata.pixelIsPoint) 0.0 else 0.5
        val positions = DoubleArray(values.size) { (values[it] - origin) / step - offset }
        val low = IntArray(values.size) { floor(positions[it]).toLong().coerceIn(0, limit.toLong() - 1).toInt() }
        val high = IntArray(values.size) { (floor(positions[it]).toLong() + 1).coerceIn(0, limit.toLong() - 1).toInt() }
        val cells = (low.asList() + high.asList()).distinct().sorted().toIntArray()
        val lookup = cells.withIndex().associate { it.value to it.index }
        return ReliefAxis(cells, IntArray(values.size) { lookup.getValue(low[it]) },
            IntArray(values.size) { lookup.getValue(high[it]) },
            DoubleArray(values.size) { positions[it] - floor(positions[it]) },
            BooleanArray(values.size) { positions[it] + 0.5 >= 0 && positions[it] + 0.5 < limit })
    }
}
