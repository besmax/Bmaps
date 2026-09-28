package bes.max.bmaps.core.storage

import bes.max.bmaps.core.tiff.NativeDem

internal actual fun openNativeDem(path: String): NativeDemHandle {
    val pointer = NativeDem.open(path.encodeToByteArray())
    if (pointer <= 0) demFailure((-pointer).toInt())
    return object : NativeDemHandle {
        override fun metadata(): DemMetadata = demMetadata(NativeDem.metadata(pointer))

        override fun sample(column: Int, row: Int): DemSample {
            val value = DoubleArray(1)
            val status = NativeDem.sample(pointer, column, row, value)
            return demSample(status, value[0])
        }

        override fun close() = NativeDem.close(pointer)
    }
}
