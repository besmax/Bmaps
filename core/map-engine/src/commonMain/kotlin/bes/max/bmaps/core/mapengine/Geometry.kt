/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
        val Pulkovo1942 = CoordinateSystemId("EPSG:4284")
        val Pz9011 = CoordinateSystemId("EPSG:9475")
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
    data class Success(val coordinate: ProjectedCoordinate, val operation: CoordinateOperation? = null) : TransformResult
    data object UnsupportedCoordinateSystem : TransformResult
    data object OutsideCoverage : TransformResult
    data object MissingTransformationData : TransformResult
    data object Failed : TransformResult
}

data class CoordinateOperation(
    val name: String,
    val accuracyMeters: Double,
    val coordinateEpoch: Double? = null,
)
