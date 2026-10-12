/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.serialization.Serializable

@Serializable
enum class ActivityProfile { HIKING, OFF_ROAD_MOTORCYCLE, ROAD_TRAVEL }

@Serializable
enum class RouteDirection { FORWARD, REVERSE }

@Serializable
data class ActivityTime(
    val epochMillis: Long,
    val monotonicMillis: Long?,
    val continuityId: String,
)

interface ActivityClock {
    fun now(): ActivityTime
}

@Serializable
data class RouteVertex(val coordinate: GeographicCoordinate, val elevation: ElevationMeasurement? = null)

@Serializable
data class RouteSegment(val id: String, val vertices: List<RouteVertex>)

@Serializable
data class RoutePosition(val segmentIndex: Int, val edgeIndex: Int, val fraction: Double)

@Serializable
data class RouteControlPoint(val id: String, val name: String, val position: RoutePosition)

@Serializable
data class ActivityRoute(
    val id: String,
    val revision: Long,
    val name: String,
    val segments: List<RouteSegment>,
    val controlPoints: List<RouteControlPoint> = emptyList(),
    val schemaVersion: Int = 1,
)

@Serializable
data class RouteSelection(
    val snapshot: ActivityRoute,
    val direction: RouteDirection,
    val start: RoutePosition,
)

@Serializable
enum class ActivityStatus { RECORDING, PAUSED, INTERRUPTED, FINALIZING, COMPLETED }

@Serializable
data class ActivityRecord(
    val id: String,
    val revision: Long,
    val sessionGeneration: String,
    val name: String,
    val profile: ActivityProfile,
    val status: ActivityStatus,
    val startedAt: ActivityTime,
    val updatedAt: ActivityTime,
    val route: RouteSelection? = null,
    val finishedAt: ActivityTime? = null,
    val derivedRouteId: String? = null,
    val schemaVersion: Int = 1,
    val controlRevision: Long = 0,
)

@Serializable
enum class ActivityEventKind {
    STARTED, PAUSED, RESUMED, INTERRUPTED, FINISH_REQUESTED, COMPLETED,
    POSITION_LOST, POSITION_RECOVERED, SOURCE_CONFLICT,
    WEARABLE_DISCONNECTED, WEARABLE_RECONNECTED, TELEMETRY_GAP, CONTROL_POINT,
}

@Serializable
data class ActivityEvent(
    val id: String,
    val sequence: Long,
    val kind: ActivityEventKind,
    val time: ActivityTime,
    val relatedId: String? = null,
)

@Serializable
data class ActivityDurations(
    val elapsedMillis: Long,
    val recordingMillis: Long,
    val movingMillis: Long,
    val interruptedMillis: Long,
    val elapsedIsEstimated: Boolean = false,
)

@Serializable
enum class RouteMatchQuality { MATCHED, UNCERTAIN, OFF_ROUTE, POSITION_UNAVAILABLE }

@Serializable
data class RouteProgress(
    val quality: RouteMatchQuality,
    val position: RoutePosition? = null,
    val completedRouteMeters: Double? = null,
    val remainingRouteMeters: Double? = null,
    val deviationMeters: Double? = null,
    val nextControlPointId: String? = null,
)
