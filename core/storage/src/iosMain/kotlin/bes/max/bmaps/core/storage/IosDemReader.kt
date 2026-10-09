/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.storage.native.*
import kotlinx.cinterop.*

internal actual fun openNativeDem(path: String): NativeDemHandle = memScoped {
    val status = alloc<IntVar>()
    val pointer = bmaps_dem_open(path, status.ptr) ?: demFailure(status.value)
    object : NativeDemHandle {
        override fun metadata(): DemMetadata = memScoped {
            val values = allocArray<DoubleVar>(BMAPS_DEM_METADATA_COUNT)
            bmaps_dem_metadata(pointer, values)
            demMetadata(DoubleArray(BMAPS_DEM_METADATA_COUNT) { values[it] })
        }

        override fun sample(column: Int, row: Int): DemSample = memScoped {
            val value = alloc<DoubleVar>()
            val result = bmaps_dem_sample(pointer, column.toUInt(), row.toUInt(), value.ptr)
            demSample(result, if (result == 0) value.value else 0.0)
        }

        override fun samples(columns: IntArray, row: Int): DoubleArray {
            val values = DoubleArray(columns.size)
            val status = columns.usePinned { indices -> values.usePinned { output ->
                bmaps_dem_samples(pointer, indices.addressOf(0), columns.size.toUInt(), row, output.addressOf(0))
            } }
            if (status != 0) demFailure(status)
            return values
        }

        override fun enableCache() = bmaps_dem_enable_cache(pointer)
        override fun metrics(): DoubleArray = DoubleArray(5).also { values ->
            values.usePinned { bmaps_dem_metrics(pointer, it.addressOf(0)) }
        }
        override fun grid(columns: IntArray, rows: IntArray): DoubleArray {
            val values = DoubleArray(columns.size * rows.size)
            val status = columns.usePinned { x -> rows.usePinned { y -> values.usePinned { output ->
                bmaps_dem_grid(pointer, x.addressOf(0), columns.size.toUInt(), y.addressOf(0), rows.size.toUInt(), output.addressOf(0))
            } } }
            if (status != 0) demFailure(status)
            return values
        }
        override fun range(firstBlock: Long): DoubleArray {
            val values = DoubleArray(5)
            val status = values.usePinned { bmaps_dem_range(pointer, firstBlock.toUInt(), it.addressOf(0)) }
            if (status != 0) demFailure(status)
            return values
        }

        override fun close() = bmaps_dem_close(pointer)
    }
}
