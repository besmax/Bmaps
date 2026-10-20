/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.activity.model.ActivityDataAvailability
import bes.max.bmaps.domain.activity.model.ActivityDurations
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityProfile
import bes.max.bmaps.domain.activity.model.ActivityRecord
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.ActivityTime
import bes.max.bmaps.domain.activity.model.HeartRateMeasurement
import bes.max.bmaps.domain.activity.model.MeasurementPhase
import bes.max.bmaps.domain.activity.model.MeasurementSource
import bes.max.bmaps.domain.activity.model.MeasurementSourceKind
import bes.max.bmaps.domain.activity.model.PositionMeasurement
import bes.max.bmaps.domain.activity.model.RouteMatchQuality
import bes.max.bmaps.domain.activity.model.RouteProgress
import bes.max.bmaps.domain.activity.model.SpeedMeasurement
import bes.max.bmaps.domain.activity.model.TelemetrySample
import bes.max.bmaps.domain.activity.recording.ActivityLiveState
import bmaps.feature.viewer.generated.resources.*
import kotlin.test.*
import kotlinx.collections.immutable.toImmutableList

class TrackPresentationTest {
    @Test fun pausedAndUnreliableMeasurementsAreNotPresentedAsLive() {
        val point = sample("point", "segment", 55.0, 37.0)
        val speed = point.copy(id = "speed", measurement = SpeedMeasurement(10.0))
        val heart = point.copy(id = "heart", measurement = HeartRateMeasurement(110.0))
        val record = ActivityRecord("trip", 0, "generation", "Trip", ActivityProfile.ROAD_TRAVEL,
            ActivityStatus.RECORDING, point.acquiredAt, point.acquiredAt)
        val live = ActivityLiveState(record, 1234.0, ActivityDurations(7200_000, 6000_000, 5000_000, 0),
            point, speed, heart, positionAvailability = ActivityDataAvailability.LIVE, heartRateAvailability = ActivityDataAvailability.LIVE)
        assertEquals("36.0 km/h", trackMetrics(live, null).primary)
        assertEquals("110", trackMetrics(live, null).heartRate)
        assertEquals("1.2", trackMetrics(live, null).distance)
        assertEquals("2:00:00", trackMetrics(live, null).elapsed)
        assertEquals("— km/h", trackMetrics(live.copy(positionAvailability = ActivityDataAvailability.SOURCE_CONFLICT), null).primary)
        val paused = trackMetrics(live.copy(record = record.copy(status = ActivityStatus.PAUSED)), null)
        assertEquals("— km/h", paused.primary)
        assertEquals("—", paused.heartRate)
        assertTrue(paused.resumable)
        assertEquals(Res.string.track_recording_failed, trackMetrics(live.copy(failure = ActivityFailure.StorageUnavailable), null).status)
    }

    @Test fun hikingPaceAndUnknownRouteFieldsRemainSeparate() {
        val point = sample("point", "segment", 0.0, 0.0)
        val record = ActivityRecord("trip", 0, "generation", "Hike", ActivityProfile.HIKING, ActivityStatus.RECORDING, point.acquiredAt, point.acquiredAt)
        val live = ActivityLiveState(record, 100.0, ActivityDurations(0, 0, 0, 0), point,
            point.copy(measurement = SpeedMeasurement(2.0)), positionAvailability = ActivityDataAvailability.LIVE)
        val metrics = trackMetrics(live, null)
        assertEquals("8:20 min/km", metrics.primary)
        assertNull(metrics.remaining)
        assertNull(metrics.deviation)
        assertEquals("—", metrics.heartRate)
        assertFalse(metrics.hasNavigation)
    }

    @Test fun actualDistanceRouteProgressAndRecordingTimeAreIndependent() {
        val point = sample("point", "segment", 0.0, 0.0)
        val record = ActivityRecord("trip", 0, "generation", "Trip", ActivityProfile.ROAD_TRAVEL,
            ActivityStatus.PAUSED, point.acquiredAt, point.acquiredAt)
        val live = ActivityLiveState(record, 6_200.0, ActivityDurations(120_000, 60_000, 50_000, 0),
            progress = RouteProgress(RouteMatchQuality.MATCHED, completedRouteMeters = 5_800.0,
                remainingRouteMeters = 10_200.0, plannedDistanceMeters = 16_000.0))
        val metrics = trackMetrics(live, null)
        assertEquals("6.2", metrics.distance)
        assertEquals("5.8 / 16.0 km", metrics.routeProgress)
        assertEquals("0:01:00", metrics.recordingTime)
        assertEquals("0:02:00", metrics.elapsed)
        assertEquals("— km/h", metrics.speed)
    }

    @Test fun pathBufferNeverConnectsPauseSegmentsAndPreservesEndpointsWhileThinning() {
        val buffer = TrackPathBuffer()
        repeat(21_000) { index -> buffer.add(sample("p$index", "first", 0.0, index / 100000.0)) }
        buffer.add(sample("last", "second", 1.0, 1.0))
        val display = buffer.snapshot()
        assertEquals(2, display.size)
        assertTrue(display.sumOf { it.points.size } <= 20_000)
        assertEquals(TrackCoordinate(0.0, 0.0), display.first().points.first())
        assertEquals(TrackCoordinate(0.0, 20999 / 100000.0), display.first().points.last())
        assertEquals(TrackCoordinate(1.0, 1.0), display.last().points.single())
        buffer.add(sample("last", "second", 1.0, 1.0))
        assertEquals(display, buffer.snapshot())
    }

    @Test fun overlaysClipCrossingEdgesAndDoNotInventSegmentOrDatelineConnectors() {
        val pyramid = TilePyramid(ZoomRange(2, 2), 1, 1, 1, 1)
        val crossing = TrackLine("crossing", listOf(TrackCoordinate(30.0, -100.0), TrackCoordinate(30.0, 10.0)).toImmutableList())
        val state = TrackState(recorded = listOf(crossing).toImmutableList())
        val path = TrackMapLayer.paths(state, pyramid).single()
        assertEquals(0.0, path.points.first().x, 1e-9)
        assertEquals(1.0, path.points.last().x, 1e-9)
        assertTrue(path.points.all { it.isValid })
        assertFalse(path.clickable)
        val world = TilePyramid(ZoomRange(0, 0), 0, 0, 1, 1)
        val dateline = TrackLine("date", listOf(TrackCoordinate(0.0, 179.0), TrackCoordinate(0.0, -179.0)).toImmutableList())
        assertTrue(TrackMapLayer.paths(state.copy(recorded = listOf(dateline).toImmutableList()), world).isEmpty())
        val singletons = listOf(TrackLine("a", listOf(TrackCoordinate(0.0, 0.0)).toImmutableList()),
            TrackLine("b", listOf(TrackCoordinate(1.0, 1.0)).toImmutableList())).toImmutableList()
        assertTrue(TrackMapLayer.paths(state.copy(recorded = singletons), world).isEmpty())
    }

    private fun sample(id: String, segment: String, lat: Double, lon: Double) = TelemetrySample(id,
        MeasurementSource("phone", MeasurementSourceKind.PHONE_LOCATION), ActivityTime(1000, 1000, "clock"), ActivityTime(1000, 1000, "clock"),
        MeasurementPhase.RECORDING, PositionMeasurement(GeographicCoordinate(lat, lon), 3.0), segment)
}
