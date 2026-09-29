@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.core.mapengine

import bes.max.bmaps.core.mapengine.native.*
import kotlinx.cinterop.*

internal actual fun openNativeProjection(): NativeProjectionHandle {
    val pointer = checkNotNull(bmaps_projection_open()) { "Cannot allocate PROJ context" }
    return object : NativeProjectionHandle {
        override fun transform(coordinate: ProjectedCoordinate, target: CoordinateSystemId): TransformResult = memScoped {
            val values = allocArray<DoubleVar>(4)
            val status = bmaps_projection_transform(pointer, coordinate.coordinateSystem.value.removePrefix("EPSG:").toInt(),
                target.value.removePrefix("EPSG:").toInt(), coordinate.x, coordinate.y, values)
            projectionResult(status, target, if (status == 0) DoubleArray(4) { values[it] } else DoubleArray(4),
                if (status == 0) bmaps_projection_operation(pointer)?.toKString().orEmpty() else "")
        }

        override fun close() = bmaps_projection_close(pointer)
    }
}
