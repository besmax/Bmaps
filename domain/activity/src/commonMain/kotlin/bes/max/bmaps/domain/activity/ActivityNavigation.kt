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
enum class ActivityNavigationMode { PLANNED_ROUTE, RECORDED_RETURN }

@Serializable
enum class ActivityNavigationAction { CORRECT_POSITION, RETURN_ALONG_RECORDING, FOLLOW_PLANNED, SET_ALERTS_ENABLED }

@Serializable
data class ActivityNavigationCommand(
    val id: String,
    val activityId: String,
    val sessionGeneration: String,
    val expectedNavigationRevision: Long,
    val action: ActivityNavigationAction,
    val position: RoutePosition? = null,
    val alertsEnabled: Boolean? = null,
)

@Serializable
data class ActivityNavigationState(
    val mode: ActivityNavigationMode,
    val routeId: String,
    val routeRevision: Long,
    val direction: RouteDirection,
    val origin: RoutePosition,
    val checkpoint: RoutePosition,
    val revision: Long = 0,
    val alertsEnabled: Boolean = true,
    val deviated: Boolean = false,
    val lastDeviationAlertAt: ActivityTime? = null,
    val endAnnounced: Boolean = false,
    val gapAnnouncedSegmentId: String? = null,
)

@Serializable
enum class RouteUncertainty { ACQUIRING, AMBIGUOUS_SECTION, CONTINUITY_LOST, WRONG_DIRECTION, SEARCH_LIMIT, POSITION_QUALITY }

@Serializable
data class RouteGap(
    val fromSegmentId: String,
    val toSegmentId: String,
    val from: GeographicCoordinate,
    val to: GeographicCoordinate,
)

data class ActivityNavigationAlert(
    val id: String,
    val activityId: String,
    val routeId: String,
    val kind: ActivityEventKind,
    val time: ActivityTime,
    val relatedId: String? = null,
)

data class ActivityPositionQuery(val afterSequence: Long = 0, val limit: Int = 500)
data class ActivityPositionPage(val items: List<TelemetrySample>, val nextSequence: Long?)

data class RouteNavigationPolicy(
    val onRouteMeters: Double,
    val offRouteMeters: Double,
    val searchRadiusMeters: Double,
    val forwardWindowMeters: Double,
    val backwardWindowMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val endpointMeters: Double,
    val deviationHoldMillis: Long,
    val rejoinHoldMillis: Long = 3_000,
    val repeatAlertMillis: Long = 60_000,
    val confirmationFixes: Int = 2,
    val maxVisitedNodes: Int = 4_096,
    val maxProjectedEdges: Int = 4_096,
) {
    companion object {
        fun forProfile(profile: ActivityProfile) = when (profile) {
            ActivityProfile.HIKING -> RouteNavigationPolicy(15.0, 30.0, 200.0, 35.0, 15.0, 12.0, 10.0, 8_000)
            ActivityProfile.OFF_ROAD_MOTORCYCLE -> RouteNavigationPolicy(25.0, 55.0, 500.0, 90.0, 30.0, 80.0, 20.0, 6_000)
            ActivityProfile.ROAD_TRAVEL -> RouteNavigationPolicy(20.0, 45.0, 500.0, 100.0, 30.0, 90.0, 20.0, 6_000)
        }
    }
}
