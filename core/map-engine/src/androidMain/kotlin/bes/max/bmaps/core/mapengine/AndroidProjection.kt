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
