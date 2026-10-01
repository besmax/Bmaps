/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import bes.max.bmaps.core.proj.NativeProjection

internal actual fun openNativeProjection(): NativeProjectionHandle {
    val pointer = NativeProjection.open()
    check(pointer != 0L) { "Cannot allocate PROJ context" }
    return object : NativeProjectionHandle {
        override fun transform(coordinate: ProjectedCoordinate, target: CoordinateSystemId): TransformResult {
            val values = DoubleArray(4)
            val status = NativeProjection.transform(pointer, coordinate.coordinateSystem.value.removePrefix("EPSG:").toInt(),
                target.value.removePrefix("EPSG:").toInt(), coordinate.x, coordinate.y, values)
            return projectionResult(status, target, values, if (status == 0) NativeProjection.operation(pointer) else "")
        }

        override fun close() = NativeProjection.close(pointer)
    }
}
