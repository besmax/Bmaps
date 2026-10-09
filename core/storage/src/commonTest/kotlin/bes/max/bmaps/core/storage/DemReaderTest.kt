/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DemReaderTest {
    private fun metadata(point: Boolean = false) = DemMetadata(
        7, 5, 10.0, 50.0, 0.25, -0.25, point, 16, 2, 1, false, 28,
    )

    @Test
    fun areaCellsUseCentersAndExcludeEastAndSouthEdges() {
        val grid = metadata()
        assertEquals(0 to 0, grid.cell(50.0, 10.0))
        assertEquals(0 to 0, grid.cell(49.875, 10.125))
        assertEquals(1 to 1, grid.cell(49.75, 10.25))
        assertEquals(6 to 4, grid.cell(48.875, 11.625))
        assertNull(grid.cell(50.0, 11.75))
        assertNull(grid.cell(48.75, 10.0))
        assertNull(grid.cell(50.001, 10.0))
        assertNull(grid.cell(50.0, 9.999))
        assertNull(grid.cell(Double.NaN, 10.0))
        assertNull(grid.cell(50.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun pointCellsHaveHalfPixelFootprintWithoutShiftingSamples() {
        val grid = metadata(true)
        assertEquals(0 to 0, grid.cell(50.0, 10.0))
        assertEquals(0 to 0, grid.cell(50.125, 9.875))
        assertEquals(1 to 1, grid.cell(49.875, 10.125))
        assertEquals(6 to 4, grid.cell(49.0, 11.5))
        assertNull(grid.cell(48.875, 10.0))
        assertNull(grid.cell(50.0, 11.625))
    }

    @Test
    fun readerOwnsHandleAndNeverCallsNativeOutsideCoverageOrAfterClose() = runTest {
        var reads = 0
        var closes = 0
        val handle = object : NativeDemHandle {
            override fun metadata() = this@DemReaderTest.metadata()
            override fun sample(column: Int, row: Int): DemSample {
                reads++
                return if (column == 0) DemSample.Value(0.0) else DemSample.NoData
            }
            override fun close() { closes++ }
        }
        val reader = DemReader(handle, handle.metadata())
        assertEquals(DemSample.Value(0.0), reader.sample(50.0, 10.0))
        assertEquals(DemSample.NoData, reader.sampleCell(1, 0))
        assertEquals(DemSample.OutsideCoverage, reader.sampleCell(-1, 0))
        assertEquals(DemSample.OutsideCoverage, reader.sample(0.0, 0.0))
        assertEquals(2, reads)
        reader.close()
        reader.close()
        assertEquals(1, closes)
        assertEquals(DemFailure.CLOSED, assertFailsWith<DemReadException> { reader.sampleCell(0, 0) }.reason)
        assertEquals(2, reads)
    }
    @Test
    fun gridReadsAreBoundedAndRespectReaderOwnership() = runTest {
        var grids = 0
        var cacheEnabled = false
        val handle = object : NativeDemHandle {
            override fun metadata() = this@DemReaderTest.metadata()
            override fun sample(column: Int, row: Int) = DemSample.Value((row * 7 + column).toDouble())
            override fun grid(columns: IntArray, rows: IntArray): DoubleArray {
                grids++
                return super.grid(columns, rows)
            }
            override fun enableCache() { cacheEnabled = true }
            override fun close() {}
        }
        val reader = DemReader(handle, handle.metadata())
        reader.enableRasterCache()
        assertEquals(true, cacheEnabled)
        kotlin.test.assertContentEquals(doubleArrayOf(17.0, 14.0, 3.0, 0.0), reader.readGrid(intArrayOf(3, 0), intArrayOf(2, 0)))
        assertFailsWith<IllegalArgumentException> { reader.readGrid(IntArray(8192), IntArray(17)) }
        assertFailsWith<IllegalArgumentException> { reader.readGrid(IntArray(0), intArrayOf(0)) }
        assertEquals(1, grids)
        reader.close()
        assertEquals(DemFailure.CLOSED, assertFailsWith<DemReadException> { reader.readGrid(intArrayOf(0), intArrayOf(0)) }.reason)
        assertEquals(1, grids)
    }

}
