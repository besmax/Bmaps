/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlin.test.*

class ActivityRecordingStatisticsTest {
    @Test
    fun monotonicTimeIgnoresWallClockRollbackAndPauseExcludesRecording() {
        val start = ActivityTime(10_000, 1_000, "process")
        val record = ActivityRecord("activity", 0, "generation", "Trip", ActivityProfile.HIKING, ActivityStatus.RECORDING, start, start)
        val atPause = ActivityTime(5_000, 3_000, "process")
        val statistics = record.statisticsAt(atPause)
        assertEquals(2_000L, statistics.elapsedMillis)
        assertEquals(2_000L, statistics.recordingMillis)
        val paused = record.copy(status = ActivityStatus.PAUSED, updatedAt = atPause, statistics = statistics)
            .statisticsAt(ActivityTime(6_000, 4_000, "process"))
        assertEquals(3_000L, paused.elapsedMillis)
        assertEquals(2_000L, paused.recordingMillis)
        assertFalse(paused.elapsedIsEstimated)
    }

    @Test
    fun unknownContinuityNeverAddsRecordingOrMovingTime() {
        val start = ActivityTime(10_000, 1_000, "process")
        val record = ActivityRecord("activity", 0, "generation", "Trip", ActivityProfile.HIKING, ActivityStatus.INTERRUPTED, start, start)
        val stats = record.statisticsAt(ActivityTime(15_000, 100, "next-process"))
        assertEquals(5_000L, stats.elapsedMillis)
        assertEquals(5_000L, stats.interruptedMillis)
        assertEquals(0L, stats.recordingMillis)
        assertEquals(0L, stats.movingMillis)
        assertTrue(stats.elapsedIsEstimated)
        assertNull(ActivityValidation.durations(stats.durations()))
    }

    @Test
    fun movementDoesNotBridgeSegmentsOrLongGaps() {
        val movement = ActivityMovement(ActivityProfile.HIKING)
        var stats = ActivityStatistics(elapsedMillis = 20_000, recordingMillis = 20_000)
        stats = movement.accept(point(1_000, 55.0, "a"), 2.0, stats)
        stats = movement.accept(point(2_000, 55.0001, "a"), 2.0, stats)
        val distance = stats.distanceMeters
        assertTrue(distance in 10.0..12.0)
        assertEquals(1_000L, stats.movingMillis)
        stats = movement.accept(point(3_000, 56.0, "b"), 2.0, stats)
        assertEquals(distance, stats.distanceMeters)
        stats = movement.accept(point(20_000, 57.0, "b"), 2.0, stats)
        assertEquals(distance, stats.distanceMeters)
        assertEquals(1_000L, stats.movingMillis)
    }

    @Test
    fun stationaryJitterAndDuplicateFixesDoNotCountMovement() {
        val movement = ActivityMovement(ActivityProfile.HIKING)
        var stats = ActivityStatistics(elapsedMillis = 2_000, recordingMillis = 2_000)
        stats = movement.accept(point(1_000, 55.0, "a"), 0.0, stats)
        stats = movement.accept(point(1_000, 55.00001, "a"), 2.0, stats)
        stats = movement.accept(point(2_000, 55.00002, "a"), 0.0, stats)
        assertEquals(0.0, stats.distanceMeters)
        assertEquals(0L, stats.movingMillis)
    }

    private fun point(time: Long, latitude: Double, segment: String) = TelemetrySample("point-$time", MeasurementSource("gps", MeasurementSourceKind.PHONE_LOCATION),
        ActivityTime(time, time, "process"), ActivityTime(time, time, "process"), MeasurementPhase.RECORDING,
        PositionMeasurement(GeographicCoordinate(latitude, 37.0), 3.0), segment)
}
