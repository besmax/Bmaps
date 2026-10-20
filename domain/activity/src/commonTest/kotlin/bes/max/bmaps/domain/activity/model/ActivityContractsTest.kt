/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.model

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationMode
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationState
import bes.max.bmaps.domain.activity.recording.ActivityAction
import bes.max.bmaps.domain.activity.recording.ActivityLifecycle
import bes.max.bmaps.domain.activity.wearable.WearableCommand
import bes.max.bmaps.domain.activity.wearable.WearableCommandAction
import bes.max.bmaps.domain.activity.wearable.WearableHeartRateBatch
import bes.max.bmaps.domain.activity.wearable.WearableHeartRateSample
import bes.max.bmaps.domain.activity.wearable.WearableValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ActivityContractsTest {
    @Test
    fun finalizationCannotResumeOrBeInterrupted() {
        assertNull(ActivityLifecycle.next(ActivityStatus.FINALIZING, ActivityAction.RESUME))
        assertNull(ActivityLifecycle.next(ActivityStatus.FINALIZING, ActivityAction.INTERRUPT))
        assertEquals(ActivityStatus.COMPLETED, ActivityLifecycle.next(ActivityStatus.FINALIZING, ActivityAction.COMPLETE))
        assertNull(ActivityLifecycle.next(ActivityStatus.RECORDING, ActivityAction.COMPLETE))
        assertNull(ActivityLifecycle.next(ActivityStatus.COMPLETED, ActivityAction.RESUME))
    }

    @Test
    fun recoveryRequiresExplicitResumeAndRepeatedPauseIsIdempotent() {
        assertEquals(ActivityStatus.INTERRUPTED, ActivityLifecycle.next(ActivityStatus.RECORDING, ActivityAction.INTERRUPT))
        assertEquals(ActivityStatus.RECORDING, ActivityLifecycle.next(ActivityStatus.INTERRUPTED, ActivityAction.RESUME))
        assertEquals(ActivityStatus.PAUSED, ActivityLifecycle.next(ActivityStatus.PAUSED, ActivityAction.PAUSE))
        assertNull(ActivityLifecycle.next(ActivityStatus.INTERRUPTED, ActivityAction.PAUSE))
    }

    @Test
    fun routeSnapshotRetainsSeparateSegmentsAndReverseStartAcrossSerialization() {
        val selection = RouteSelection(route(), RouteDirection.REVERSE, RoutePosition(1, 0, 1.0))
        val decoded = Json.decodeFromString<RouteSelection>(Json.encodeToString(selection))
        assertEquals(selection, decoded)
        assertNull(ActivityValidation.selection(decoded))
        assertNotNull(ActivityValidation.selection(decoded.copy(start = RoutePosition(2, 0, 0.0))))
        assertNotNull(ActivityValidation.selection(decoded.copy(start = RoutePosition(0, 1, 0.0))))
    }

    @Test
    fun detailedRoutesKeepTheExistingAnnotationLimitIndependent() {
        val vertices = (0..1_500).map { RouteVertex(GeographicCoordinate(50.0, 30.0 + it * 0.00001)) }
        assertNull(ActivityValidation.route(route().copy(segments = listOf(RouteSegment("detailed", vertices)))))
        assertNotNull(ActivityValidation.route(route().copy(segments = List(ActivityLimits.MAX_ROUTE_SEGMENTS + 1) { route().segments.first() })))
        assertNotNull(ActivityValidation.route(route().copy(segments = listOf(route().segments.first(), route().segments.first()))))
    }

    @Test
    fun manualPauseRejectsHeartRateAndOtherTelemetry() {
        val time = ActivityTime(1_000, 10, "boot-1")
        val sample = TelemetrySample("sample", MeasurementSource("watch", MeasurementSourceKind.WATCH_HEALTH),
            time, time, MeasurementPhase.PAUSED, HeartRateMeasurement(124.0))
        assertEquals("phase", ActivityValidation.sample(sample))
        assertEquals(sample, Json.decodeFromString<TelemetrySample>(Json.encodeToString(sample)))
        assertEquals("phase", ActivityValidation.sample(sample.copy(
            measurement = PositionMeasurement(GeographicCoordinate(50.0, 30.0), 5.0), pathSegmentId = "segment-1")))
        assertEquals("phase", ActivityValidation.sample(sample.copy(measurement = SpeedMeasurement(2.0))))
        assertEquals("phase", ActivityValidation.sample(sample.copy(measurement = ElevationMeasurement(120.0, "EGM96"))))
        assertEquals("phase", ActivityValidation.sample(sample.copy(measurement = CourseMeasurement(90.0))))
        assertNull(ActivityValidation.sample(sample.copy(phase = MeasurementPhase.RECORDING)))
        assertEquals("heartRate", ActivityValidation.sample(sample.copy(phase = MeasurementPhase.RECORDING,
            measurement = HeartRateMeasurement(Double.NaN))))
    }

    @Test
    fun finishBoundaryIsRequiredOnlyForFinalizingAndCompletedRecords() {
        val time = ActivityTime(1_000, 10, "boot-1")
        val record = ActivityRecord("activity", 0, "generation", "", ActivityProfile.HIKING,
            ActivityStatus.RECORDING, time, time)
        assertNull(ActivityValidation.record(record))
        assertNotNull(ActivityValidation.record(record.copy(status = ActivityStatus.COMPLETED)))
        assertNull(ActivityValidation.record(record.copy(status = ActivityStatus.COMPLETED, finishedAt = time)))
    }

    @Test
    fun plannedNavigationAcceptsValidRecordsAndRejectsMissingOrMismatchedSelection() {
        val snapshot = route()
        val time = ActivityTime(1_000, 10, "boot-1")
        for (direction in RouteDirection.entries) {
            val start = RoutePosition(1, 0, if (direction == RouteDirection.REVERSE) 1.0 else 0.0)
            val selection = RouteSelection(snapshot, direction, start)
            val navigation = ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE,
                snapshot.id, snapshot.revision, direction, start, start)
            val record = ActivityRecord("activity", 0, "generation", "Trip", ActivityProfile.HIKING,
                ActivityStatus.RECORDING, time, time, route = selection, navigation = navigation)
            assertNull(ActivityValidation.record(record))
            assertEquals("navigationRoute", ActivityValidation.record(record.copy(route = null)))
            assertEquals("navigationSelection", ActivityValidation.record(record.copy(
                navigation = navigation.copy(origin = RoutePosition(0, 0, 0.5)))))
            assertEquals("navigationRoute", ActivityValidation.record(record.copy(
                navigation = navigation.copy(routeId = "another-route"))))
            assertEquals("navigationRoute", ActivityValidation.record(record.copy(
                navigation = navigation.copy(checkpoint = RoutePosition(9, 0, 0.0)))))
        }
    }

    @Test
    fun wearableCommandsCannotBecomeUnlimitedDelayedActions() {
        val command = WearableCommand("command", "activity", "generation", 4, WearableCommandAction.PAUSE, 1_000, 6_000)
        assertNull(WearableValidation.command(command))
        assertEquals(command, Json.decodeFromString<WearableCommand>(Json.encodeToString(command)))
        assertEquals("expiry", WearableValidation.command(command.copy(expiresAtEpochMillis = 6_001)))
        assertEquals("protocolVersion", WearableValidation.command(command.copy(protocolVersion = 2)))
    }

    @Test
    fun wearableMeasurementsKeepAcquisitionTimeAndRejectDuplicateBatchIds() {
        val sample = WearableHeartRateSample("source-session:1000:1001", 1_000, 1_001, 124.0)
        val batch = WearableHeartRateBatch("activity", "generation", "watch", "source-session", listOf(sample))
        assertNull(WearableValidation.heartRate(batch))
        assertEquals(batch, Json.decodeFromString<WearableHeartRateBatch>(Json.encodeToString(batch)))
        assertEquals("samples", WearableValidation.heartRate(batch.copy(samples = listOf(sample, sample))))
        assertEquals("heartRate", WearableValidation.heartRate(batch.copy(samples = listOf(sample.copy(endedAtEpochMillis = 999)))))
    }

    @Test
    fun unknownRemoteMonotonicTimeDoesNotBecomeMovingTime() {
        assertEquals(true, ActivityValidation.time(ActivityTime(1_000, null, "watch-session")))
        val durations = ActivityDurations(10_000, 5_000, 4_000, 3_000, elapsedIsEstimated = true)
        assertNull(ActivityValidation.durations(durations))
        assertNotNull(ActivityValidation.durations(durations.copy(movingMillis = 6_000)))
        assertNotNull(ActivityValidation.durations(durations.copy(interruptedMillis = 6_000)))
    }

    private fun route() = ActivityRoute("route", 0, "Loop", listOf(
        RouteSegment("outbound", listOf(RouteVertex(GeographicCoordinate(50.0, 30.0)), RouteVertex(GeographicCoordinate(50.01, 30.01)))),
        RouteSegment("return", listOf(RouteVertex(GeographicCoordinate(50.1, 30.1)), RouteVertex(GeographicCoordinate(50.11, 30.11)))),
    ))
}
