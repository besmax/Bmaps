/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.model

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationCommand
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationState
import bes.max.bmaps.domain.activity.navigation.RouteGap
import bes.max.bmaps.domain.activity.navigation.RouteUncertainty
import bes.max.bmaps.domain.activity.recording.ActivityCommand
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
data class RouteVertex(
    val coordinate: GeographicCoordinate,
    val elevation: ElevationMeasurement? = null,
    val sourceTimeEpochMillis: Long? = null,
)

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
    val description: String = "",
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
    val statistics: ActivityStatistics = ActivityStatistics(),
    val lastCommand: ActivityCommand? = null,
    val navigation: ActivityNavigationState? = null,
    val lastNavigationCommand: ActivityNavigationCommand? = null,
    val packageId: String? = null,
)

@Serializable
enum class ActivityEventKind {
    STARTED, PAUSED, RESUMED, INTERRUPTED, FINISH_REQUESTED, COMPLETED,
    POSITION_LOST, POSITION_RECOVERED, SOURCE_CONFLICT,
    ROUTE_DEVIATED, ROUTE_DEVIATION_REMINDER, ROUTE_REJOINED, ROUTE_GAP, ROUTE_END,
    NAVIGATION_CORRECTED, RETURN_STARTED, PLANNED_GUIDANCE_RESTORED, NAVIGATION_ALERTS_CHANGED,
    WEARABLE_DISCONNECTED, WEARABLE_RECONNECTED, TELEMETRY_GAP, CONTROL_POINT,
}

@Serializable
data class ActivityEvent(
    val id: String,
    val sequence: Long,
    val kind: ActivityEventKind,
    val time: ActivityTime,
    val relatedId: String? = null,
    val navigationRouteId: String? = null,
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
enum class RouteMatchQuality { MATCHED, UNCERTAIN, OFF_ROUTE, POSITION_UNAVAILABLE, SEGMENT_GAP }

@Serializable
data class RouteProgress(
    val quality: RouteMatchQuality,
    val position: RoutePosition? = null,
    val completedRouteMeters: Double? = null,
    val remainingRouteMeters: Double? = null,
    val deviationMeters: Double? = null,
    val nextControlPointId: String? = null,
    val plannedDistanceMeters: Double? = null,
    val currentLegRemainingMeters: Double? = null,
    val gap: RouteGap? = null,
    val endReached: Boolean = false,
    val uncertainty: RouteUncertainty? = null,
)

@Serializable
data class ActivityStatistics(
    val distanceMeters: Double = 0.0,
    val elapsedMillis: Long = 0,
    val recordingMillis: Long = 0,
    val movingMillis: Long = 0,
    val interruptedMillis: Long = 0,
    val elapsedIsEstimated: Boolean = false,
) {
    fun durations() = ActivityDurations(elapsedMillis, recordingMillis, movingMillis, interruptedMillis, elapsedIsEstimated)
}

fun routePackageId(id: String): String? = id.takeIf { it.startsWith("map:") }?.removePrefix("map:")?.substringBeforeLast(':')
fun packageRouteId(packageId: String, routeId: String): String = "map:$packageId:${routeId.substringAfterLast(':').takeLast(36)}"
