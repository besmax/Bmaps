/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.compose.runtime.Immutable
import bes.max.bmaps.domain.activity.model.ActivityDataAvailability
import bes.max.bmaps.domain.activity.model.ActivityProfile
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.HeartRateMeasurement
import bes.max.bmaps.domain.activity.model.RouteMatchQuality
import bes.max.bmaps.domain.activity.model.SpeedMeasurement
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationMode
import bes.max.bmaps.domain.activity.recording.ActivityLiveState
import bmaps.feature.viewer.generated.resources.*
import kotlin.math.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource

@Immutable
data class TrackCoordinate(val latitude: Double, val longitude: Double)
@Immutable
internal data class TrackLine(val id: String, val points: ImmutableList<TrackCoordinate>)
@Immutable
internal data class TrackRouteItem(val id: String, val name: String, val segments: Int, val vertices: Int)
@Immutable
internal data class TrackRouteDisplay(val name: String, val length: String, val segments: Int, val edges: Int)
@Immutable
internal data class TrackMetrics(
    val name: String, val status: StringResource, val elapsed: String, val primary: String,
    val distance: String, val remaining: String?, val deviation: String?, val controlPoint: String?,
    val quality: StringResource, val navigationQuality: StringResource?, val heartRate: String,
    val recordingTime: String, val movingTime: String, val endReached: Boolean,
    val recording: Boolean, val resumable: Boolean, val finalizing: Boolean, val failed: Boolean,
    val routeProgress: String? = null, val course: String = "—", val speed: String = "— km/h",
    val hasPlan: Boolean, val hasNavigation: Boolean, val returning: Boolean, val alertsEnabled: Boolean,
)
@Immutable
internal data class TrackState(
    val pickingStart: Boolean = false, val pickLoading: Boolean = false,
    val startFraction: Double = 0.0, val startDistance: String = "0.0",
    val startCoordinate: TrackCoordinate? = null, val startBearing: Float? = null,
    val pickOptions: ImmutableList<TrackStartPick> = persistentListOf(), val pickOption: Int = 0,
    val markerCourse: Float? = null,
    val packageId: String? = null, val panelCollapsed: Boolean = false,
    val expanded: Boolean = false, val menu: Boolean = false, val preparing: Boolean = false,
    val choosing: Boolean = false, val correcting: Boolean = false, val finishConfirmation: Boolean = false,
    val details: Boolean = false, val busy: Boolean = false, val following: Boolean = false,
    val profile: Int = 0, val reverse: Boolean = false, val segment: Int = 0, val edge: Int = 0,
    val selectedAnnotationId: String? = null, val selectedAnnotationLength: String? = null,
    val route: TrackRouteDisplay? = null, val routes: ImmutableList<TrackRouteItem> = persistentListOf(),
    val moreRoutes: Boolean = false, val coverageMissing: Boolean = false, val metrics: TrackMetrics? = null,
    val selectedSection: ImmutableList<TrackLine> = persistentListOf(),
    val preview: ImmutableList<TrackLine> = persistentListOf(),
    val planned: ImmutableList<TrackLine> = persistentListOf(),
    val guidance: ImmutableList<TrackLine> = persistentListOf(),
    val recorded: ImmutableList<TrackLine> = persistentListOf(),
    val position: TrackCoordinate? = null, val positionId: String? = null,
    val error: StringResource? = null,
)
internal sealed interface TrackEvent {
    data class Started(val packageId: String?) : TrackEvent
    data class Completed(val activityId: String) : TrackEvent
    data class Message(val text: StringResource, val haptic: Boolean = false) : TrackEvent
}

internal fun trackDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
internal fun trackNumber(value: Double, decimals: Int = 1): String {
    val scale = 10.0.pow(decimals).toLong()
    val number = (value.coerceAtLeast(0.0) * scale).roundToLong()
    return if (decimals == 0) number.toString() else "${number / scale}.${(number % scale).toString().padStart(decimals, '0')}"
}
internal fun trackLength(route: ActivityRoute): Double = route.segments.sumOf { segment ->
    segment.vertices.zipWithNext().sumOf { (a, b) ->
        val lat = (b.coordinate.latitude - a.coordinate.latitude) * PI / 180
        val lon = (b.coordinate.longitude - a.coordinate.longitude) * PI / 180
        val h = sin(lat / 2).pow(2) + cos(a.coordinate.latitude * PI / 180) * cos(b.coordinate.latitude * PI / 180) * sin(lon / 2).pow(2)
        6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
internal fun trackMetrics(live: ActivityLiveState, route: ActivityRoute?): TrackMetrics {
    val record = live.record
    val running = record.status == ActivityStatus.RECORDING && live.failure == null
    val fresh = running && live.positionAvailability == ActivityDataAvailability.LIVE
    val speed = (live.speed?.measurement as? SpeedMeasurement)?.metersPerSecond.takeIf { fresh }
    val primary = if (record.profile == ActivityProfile.HIKING) {
        speed?.takeIf { it >= 0.1 }?.let { value ->
            val seconds = (1000 / value).roundToInt()
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} min/km"
        } ?: "— min/km"
    } else speed?.let { "${trackNumber(it * 3.6)} km/h" } ?: "— km/h"
    val progress = live.progress
    return TrackMetrics(record.name, if (live.failure != null) Res.string.track_recording_failed else when (record.status) {
        ActivityStatus.RECORDING -> Res.string.track_recording
        ActivityStatus.PAUSED -> Res.string.track_paused
        ActivityStatus.INTERRUPTED -> Res.string.track_interrupted
        ActivityStatus.FINALIZING -> Res.string.track_finalizing
        ActivityStatus.COMPLETED -> Res.string.track_completed
    }, trackDuration(live.durations.elapsedMillis), primary, trackNumber(live.distanceMeters / 1000),
        progress?.remainingRouteMeters?.let { trackNumber(it / 1000) },
        progress?.deviationMeters?.let { trackNumber(it, 0) },
        route?.controlPoints?.firstOrNull { it.id == progress?.nextControlPointId }?.name,
        when (live.positionAvailability) {
            ActivityDataAvailability.LIVE -> Res.string.track_gps_live
            ActivityDataAvailability.SOURCE_CONFLICT -> Res.string.track_gps_conflict
            ActivityDataAvailability.UNRELIABLE -> Res.string.track_gps_unreliable
            ActivityDataAvailability.STALE -> Res.string.track_gps_stale
            ActivityDataAvailability.WAITING -> Res.string.track_gps_waiting
            ActivityDataAvailability.UNAVAILABLE -> Res.string.track_gps_unavailable
        }, progress?.quality?.let { when (it) {
            RouteMatchQuality.MATCHED -> Res.string.track_matched
            RouteMatchQuality.OFF_ROUTE -> Res.string.track_off_route
            RouteMatchQuality.UNCERTAIN -> Res.string.track_uncertain
            RouteMatchQuality.POSITION_UNAVAILABLE -> Res.string.track_gps_unavailable
            RouteMatchQuality.SEGMENT_GAP -> Res.string.track_segment_gap
        } },
        (live.heartRate?.measurement as? HeartRateMeasurement)?.beatsPerMinute
            ?.takeIf { running && live.heartRateAvailability == ActivityDataAvailability.LIVE }?.let { trackNumber(it, 0) } ?: "—",
        trackDuration(live.durations.recordingMillis), trackDuration(live.durations.movingMillis),
        progress?.endReached == true, running,
        record.status == ActivityStatus.PAUSED || record.status == ActivityStatus.INTERRUPTED,
        record.status == ActivityStatus.FINALIZING, live.failure != null,
        progress?.plannedDistanceMeters?.let { total ->
            "${progress.completedRouteMeters?.let { trackNumber(it / 1000) } ?: "—"} / ${trackNumber(total / 1000)} km"
        }, "—", speed?.let { "${trackNumber(it * 3.6)} km/h" } ?: "— km/h",
        record.route != null, record.navigation != null,
        record.navigation?.mode == ActivityNavigationMode.RECORDED_RETURN,
        record.navigation?.alertsEnabled ?: true)
}
