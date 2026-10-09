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
import kotlinx.coroutines.test.runTest
import kotlin.math.floor
import kotlin.test.*

class ElevationTileSamplerTest {
    private val style = ElevationReliefStyle(ElevationReliefOptions(), -100.0, 200.0)
    private fun value(column: Int, row: Int) = if (column == 3 && row == 2) Double.NaN else column * 13.0 + row * 27.0 - 60

    @Test fun uniqueGridAndRowCachePreserveAreaAndPointInterpolation() = runTest {
        for (point in listOf(false, true)) {
            val metadata = DemMetadata(7, 5, -180.0, 80.0, 60.0, -40.0, point, 32, 3, 1, false, 140)
            var calls = 0
            var samples = 0
            val sampler = ElevationTileSampler(metadata) { columns, rows ->
                calls++
                samples += columns.size * rows.size
                DoubleArray(columns.size * rows.size) { value(columns[it % columns.size], rows[it / columns.size]) }
            }
            val tile = sampler.sample(TileKey(0, 0, 0), 256)
            val actual = tile.colors(style)
            assertEquals(1, calls)
            assertEquals(35, samples)
            val repeated = sampler.sample(TileKey(0, 0, 0), 256)
            assertContentEquals(actual, repeated.colors(style))
            assertEquals(1, calls)
            for (row in 0 until 256) for (column in 0 until 256) {
                val latitude = checkNotNull(WebMercator.geographic(MapPoint(0.5, (row + 0.5) / 256))).latitude
                val longitude = (column + 0.5) / 256 * 360 - 180
                val offset = if (point) 0.0 else 0.5
                val x = (longitude + 180) / 60 - offset
                val y = (latitude - 80) / -40 - offset
                if (x + 0.5 < 0 || x + 0.5 >= 7 || y + 0.5 < 0 || y + 0.5 >= 5) {
                    assertEquals(0, actual[row * 256 + column]); continue
                }
                val left = floor(x).toInt(); val top = floor(y).toInt()
                val fx = x - floor(x); val fy = y - floor(y)
                val weights = doubleArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy)
                val cells = doubleArrayOf(value(left.coerceIn(0, 6), top.coerceIn(0, 4)),
                    value((left + 1).coerceIn(0, 6), top.coerceIn(0, 4)),
                    value(left.coerceIn(0, 6), (top + 1).coerceIn(0, 4)),
                    value((left + 1).coerceIn(0, 6), (top + 1).coerceIn(0, 4)))
                val expected = if (cells.indices.any { weights[it] > 0 && !cells[it].isFinite() }) 0
                    else style.color(cells.indices.sumOf { if (weights[it] == 0.0) 0.0 else cells[it] * weights[it] })
                val color = actual[row * 256 + column]
                assertEquals(expected ushr 24, color ushr 24)
                for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs(((expected ushr shift) and 255) - ((color ushr shift) and 255)) <= 1)
            }
        }
    }

    @Test fun neighboringTilesMatchTheSameGlobalPixelGrid() = runTest {
        val metadata = DemMetadata(7, 5, -180.0, 80.0, 60.0, -40.0, false, 32, 3, 1, false, 140)
        val sampler = ElevationTileSampler(metadata) { columns, rows ->
            DoubleArray(columns.size * rows.size) { value(columns[it % columns.size], rows[it / columns.size]) }
        }
        val full = sampler.sample(TileKey(0, 0, 0), 512).colors(style)
        for (tileRow in 0..1) for (tileColumn in 0..1) {
            val quarter = sampler.sample(TileKey(1, tileColumn.toLong(), tileRow.toLong()), 256).colors(style)
            for (row in 0 until 256) for (column in 0 until 256) {
                assertEquals(full[(tileRow * 256 + row) * 512 + tileColumn * 256 + column], quarter[row * 256 + column])
            }
        }
    }

    @Test fun edgeTilesAreColoredAcrossTheirWholeFootprint() = runTest {
        val metadata = DemMetadata(8, 4, -180.0, 85.0, 45.0, -42.5, false, 32, 3, 1, false, 128)
        val sampler = ElevationTileSampler(metadata) { columns, rows -> DoubleArray(columns.size * rows.size) { 10.0 } }
        val tile = sampler.sample(TileKey(8, 135, 92), 256).colors(style)
        assertTrue(tile.all { it == style.color(10.0) })
    }

    @Test fun transparentTilesAvoidNativeReads() = runTest {
        val metadata = DemMetadata(2, 2, 10.0, 50.0, 0.25, -0.25, false, 16, 2, 1, false, 8)
        val sampler = ElevationTileSampler(metadata) { _, _ -> error("Outside coverage must not read DEM") }
        assertTrue(sampler.sample(TileKey(2, 0, 0), 256).colors(style).all { it == 0 })
    }
}
