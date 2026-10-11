/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationFixSelectionTest {
    private val base = LocationFix(60.060066, 30.439427, 5.0, 100_000)

    @Test fun almostSimultaneousResultsChooseAccuracyRegardlessOfArrivalOrderOrSource() {
        val accurate = base.copy(timestampMillis = 101_000)
        val coarse = base.copy(accuracyMeters = 500.0, timestampMillis = 102_000)
        for ((preciseSource, coarseSource) in listOf("gps" to "google-fused", "google-fused" to "network", "network" to "gps")) {
            for (reverse in listOf(false, true)) {
                val selector = LocationFixSelector()
                val updates = listOf(preciseSource to accurate, coarseSource to coarse)
                for ((source, fix) in if (reverse) updates.reversed() else updates) {
                    assertTrue(selector.record(source, fix, 102_000))
                    selector.select(102_000)
                }
                assertEquals(SelectedLocationFix(preciseSource, accurate), selector.select(102_000))
            }
        }
    }

    @Test fun stoppedAccurateSourceGivesWayToNewerMovingPositionAfterWindow() {
        val selector = LocationFixSelector()
        selector.record("google-fused", base, 100_000)
        selector.select(100_000)
        val coarse = base.copy(latitude = 60.1, accuracyMeters = 500.0, timestampMillis = 103_000)
        selector.record("network", coarse, 103_000)
        assertEquals(base, selector.select(103_000)?.fix)
        val moving = coarse.copy(latitude = 60.2, timestampMillis = 103_001)
        selector.record("network", moving, 103_001)
        assertEquals(moving, selector.select(103_001)?.fix)
    }

    @Test fun eachSourcesLatestFixReplacesItsOwnOldAccurateFix() {
        val selector = LocationFixSelector()
        selector.record("gps", base, 100_000)
        selector.select(100_000)
        val moving = base.copy(latitude = 60.1, accuracyMeters = 10.0, timestampMillis = 101_000)
        selector.record("gps", moving, 101_000)
        assertEquals(moving, selector.select(101_000)?.fix)
    }

    @Test fun retainedFallbackTakesOverOnExpiryWithoutAnotherCallback() {
        val selector = LocationFixSelector()
        selector.record("gps", base, 100_000)
        selector.select(100_000)
        val fallback = base.copy(accuracyMeters = 100.0, timestampMillis = 102_000)
        selector.record("network", fallback, 102_000)
        assertEquals(base, selector.select(130_000)?.fix)
        assertEquals(fallback, selector.select(130_001)?.fix)
        assertNull(selector.select(132_001))
    }

    @Test fun invalidStaleFutureAndOutOfOrderUpdatesDoNotPoisonSelection() {
        val selector = LocationFixSelector()
        selector.record("gps", base, 100_000)
        selector.select(100_000)
        for (invalid in listOf(
            base.copy(latitude = Double.NaN), base.copy(longitude = 181.0),
            base.copy(accuracyMeters = -1.0), base.copy(accuracyMeters = Double.POSITIVE_INFINITY),
            base.copy(timestampMillis = 69_999), base.copy(timestampMillis = 105_001),
        )) assertFalse(selector.record("google-fused", invalid, 100_000))
        assertFalse(selector.record("gps", base.copy(timestampMillis = 99_999, accuracyMeters = 1.0), 100_000))
        assertEquals(base, selector.select(100_000)?.fix)
    }

    @Test fun tiesPreferNewerMeasurementAndSameTimestampCannotDegradeAccuracy() {
        val selector = LocationFixSelector()
        selector.record("gps", base, 100_000)
        val newer = base.copy(timestampMillis = 101_000)
        selector.record("google-fused", newer, 101_000)
        assertEquals(newer, selector.select(101_000)?.fix)
        assertFalse(selector.record("google-fused", newer.copy(accuracyMeters = 50.0), 101_000))
        val better = newer.copy(accuracyMeters = 2.0)
        assertTrue(selector.record("google-fused", better, 101_000))
        assertEquals(better, selector.select(101_000)?.fix)
    }

    @Test fun disabledSourceFallsBackToRetainedNewerMeasurement() {
        val selector = LocationFixSelector()
        selector.record("gps", base, 100_000)
        val fallback = base.copy(accuracyMeters = 100.0, timestampMillis = 101_000)
        selector.record("network", fallback, 101_000)
        assertEquals(base, selector.select(101_000)?.fix)
        selector.remove("gps")
        assertEquals(fallback, selector.select(101_000)?.fix)
    }

    @Test fun removingNewerSourceDoesNotRollBackToAnOlderWorsePosition() {
        val selector = LocationFixSelector()
        selector.record("network", base.copy(accuracyMeters = 500.0), 100_000)
        val newer = base.copy(timestampMillis = 101_000)
        selector.record("gps", newer, 101_000)
        selector.select(101_000)
        selector.remove("gps")
        assertNull(selector.select(101_000))
        assertNull(selector.select(102_000))
        val recovered = base.copy(accuracyMeters = 500.0, timestampMillis = 103_000)
        selector.record("network", recovered, 103_000)
        assertEquals(recovered, selector.select(103_000)?.fix)
    }

    @Test fun presentationOrderingAllowsOnlySmallAccuracyImprovingCorrections() {
        assertTrue(canReplaceLocationFix(base, base.copy(timestampMillis = 97_000, accuracyMeters = 2.0)))
        assertFalse(canReplaceLocationFix(base, base.copy(timestampMillis = 96_999, accuracyMeters = 2.0)))
        assertFalse(canReplaceLocationFix(base, base.copy(timestampMillis = 99_000, accuracyMeters = 50.0)))
        assertTrue(canReplaceLocationFix(base, base.copy(timestampMillis = 104_000, accuracyMeters = 50.0)))
    }
}
