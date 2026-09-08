package bes.max.bmaps.core.mapengine

import kotlinx.serialization.Serializable

@Serializable
data class GeographicCoordinate(val latitude: Double, val longitude: Double)

@Serializable
data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

@Serializable
data class CoordinateSystemId(val value: String) {
    companion object {
        val Wgs84 = CoordinateSystemId("EPSG:4326")
        val WebMercator = CoordinateSystemId("EPSG:3857")
    }
}

@Serializable
data class ProjectedCoordinate(
    val x: Double,
    val y: Double,
    val coordinateSystem: CoordinateSystemId,
)

interface CoordinateTransformer {
    fun supports(source: CoordinateSystemId, target: CoordinateSystemId): Boolean
    fun transform(coordinate: ProjectedCoordinate, target: CoordinateSystemId): TransformResult
}

sealed interface TransformResult {
    data class Success(val coordinate: ProjectedCoordinate) : TransformResult
    data object UnsupportedCoordinateSystem : TransformResult
    data object OutsideCoverage : TransformResult
    data object MissingTransformationData : TransformResult
}
