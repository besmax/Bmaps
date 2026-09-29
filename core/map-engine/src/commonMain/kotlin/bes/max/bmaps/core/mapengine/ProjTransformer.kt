package bes.max.bmaps.core.mapengine

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class ProjTransformerFactory {
    fun openSession(): CoordinateTransformSession = CoordinateTransformSession()
}

class CoordinateTransformSession internal constructor() {
    private val mutex = Mutex()
    private var native: NativeProjectionHandle? = null
    private var closed = false
    private var lastDiagnostic: String? = null

    suspend fun transform(coordinate: ProjectedCoordinate, target: CoordinateSystemId): TransformResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock TransformResult.Failed
                val sourceIsMercator = coordinate.coordinateSystem == CoordinateSystemId.WebMercator
                val targetIsMercator = target == CoordinateSystemId.WebMercator
                if ((!sourceIsMercator && coordinate.coordinateSystem !in geographicSystems) ||
                    (!targetIsMercator && target !in geographicSystems)) {
                    return@withLock TransformResult.UnsupportedCoordinateSystem
                }
                val geographicInput = if (sourceIsMercator) {
                    WebMercator.transform(coordinate, CoordinateSystemId.Wgs84) as? TransformResult.Success
                        ?: return@withLock TransformResult.OutsideCoverage
                } else TransformResult.Success(coordinate)
                val input = geographicInput.coordinate
                if (!input.x.isFinite() || !input.y.isFinite() ||
                    input.x !in -180.0..180.0 || input.y !in -90.0..90.0) {
                    return@withLock TransformResult.OutsideCoverage
                }
                val targetGeographic = if (targetIsMercator) CoordinateSystemId.Wgs84 else target
                if (input.coordinateSystem == targetGeographic) {
                    val geographic = TransformResult.Success(input, CoordinateOperation("Identity", 0.0))
                    return@withLock if (targetIsMercator) WebMercator.transform(input, target) else geographic
                }
                try {
                    val handle = native ?: openNativeProjection().also { native = it }
                    val projected = handle.transform(input, targetGeographic)
                    val result = if (targetIsMercator && projected is TransformResult.Success) {
                        when (val mercator = WebMercator.transform(projected.coordinate, target)) {
                            is TransformResult.Success -> mercator.copy(operation = projected.operation)
                            else -> mercator
                        }
                    } else projected
                    val diagnostic = if (result is TransformResult.Success) result.operation.toString() else result.toString()
                    val key = "${coordinate.coordinateSystem.value}->${target.value} $diagnostic"
                    if (lastDiagnostic != key) {
                        println("[BmapsCoordinates] ${if (result is TransformResult.Success) "INFO" else "ERROR"} $key")
                        lastDiagnostic = key
                    }
                    result
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val key = "${error::class.simpleName}: ${error.message}"
                    if (lastDiagnostic != key) {
                        println("[BmapsCoordinates] ERROR $key")
                        error.printStackTrace()
                        lastDiagnostic = key
                    }
                    TransformResult.Failed
                }
            }
        }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                closed = true
                native?.close()
                native = null
            }
        }
    }
}

private val geographicSystems = setOf(CoordinateSystemId.Wgs84, CoordinateSystemId.Pulkovo1942, CoordinateSystemId.Pz9011)

internal interface NativeProjectionHandle : CoordinateTransformer {
    override fun supports(source: CoordinateSystemId, target: CoordinateSystemId): Boolean =
        source in geographicSystems && target in geographicSystems
    fun close()
}

internal expect fun openNativeProjection(): NativeProjectionHandle

internal fun projectionResult(status: Int, target: CoordinateSystemId, values: DoubleArray, operation: String): TransformResult =
    when (status) {
        0 -> TransformResult.Success(ProjectedCoordinate(values[0], values[1], target),
            CoordinateOperation(operation, values[2], values[3].takeIf { it >= 0 }))
        1 -> TransformResult.UnsupportedCoordinateSystem
        2 -> TransformResult.OutsideCoverage
        3 -> TransformResult.MissingTransformationData
        else -> TransformResult.Failed
    }
