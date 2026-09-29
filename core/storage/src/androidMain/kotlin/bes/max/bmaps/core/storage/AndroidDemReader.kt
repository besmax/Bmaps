/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.tiff.NativeDem

internal actual fun openNativeDem(path: String): NativeDemHandle {
    val output = LongArray(1)
    val status = NativeDem.open(path.encodeToByteArray(), output)
    val pointer = output[0]
    println("[BmapsElevation] ${if (status == 0 && pointer != 0L) "INFO" else "ERROR"} " +
        "jni_dem_open status=$status handlePresent=${pointer != 0L}")
    if (status != 0) demFailure(status)
    if (pointer == 0L) demFailure(4)
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
