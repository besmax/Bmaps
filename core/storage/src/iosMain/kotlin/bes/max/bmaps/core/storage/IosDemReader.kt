@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.storage.native.*
import kotlinx.cinterop.*

internal actual fun openNativeDem(path: String): NativeDemHandle = memScoped {
    val status = alloc<IntVar>()
    val pointer = bmaps_dem_open(path, status.ptr) ?: demFailure(status.value)
    object : NativeDemHandle {
        override fun metadata(): DemMetadata = memScoped {
            val values = allocArray<DoubleVar>(12)
            bmaps_dem_metadata(pointer, values)
            demMetadata(DoubleArray(12) { values[it] })
        }

        override fun sample(column: Int, row: Int): DemSample = memScoped {
            val value = alloc<DoubleVar>()
            val result = bmaps_dem_sample(pointer, column.toUInt(), row.toUInt(), value.ptr)
            demSample(result, if (result == 0) value.value else 0.0)
        }

        override fun close() = bmaps_dem_close(pointer)
    }
}
