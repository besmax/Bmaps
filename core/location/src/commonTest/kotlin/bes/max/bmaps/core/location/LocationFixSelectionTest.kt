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

    @Test fun stoppedAccurateSourceGivesWayAsMovementPenaltyOutweighsAccuracy() {
        val selector = LocationFixSelector()
        selector.record("google-fused", base, 100_000)
        selector.select(100_000)
        val coarse = base.copy(latitude = base.latitude + 0.0001, accuracyMeters = 10.0, timestampMillis = 103_000)
        selector.record("network", coarse, 103_000)
        assertEquals(base, selector.select(103_000)?.fix)
        val moving = coarse.copy(latitude = base.latitude + 0.0002, timestampMillis = 106_000)
        selector.record("network", moving, 106_000)
        assertEquals(moving, selector.select(106_000)?.fix)
    }

    @Test fun sameSourceMovementEventuallyReplacesItsOlderAccurateFix() {
        val selector = LocationFixSelector()
        selector.record("gps", base, 100_000)
        selector.select(100_000)
        val moving = base.copy(latitude = 60.1, accuracyMeters = 10.0, timestampMillis = 106_000)
        selector.record("gps", moving, 106_000)
        assertEquals(moving, selector.select(106_000)?.fix)
    }

    @Test fun grosslyWorseFixCannotDisplacePrecisePositionAtFiveOrTenSeconds() {
        for (source in listOf("network", "google-fused")) {
            val selector = LocationFixSelector()
            selector.record("google-fused", base, 100_000)
            selector.select(100_000)
            for (elapsed in listOf(5_000L, 10_000L, 20_000L, 30_000L)) {
                val coarse = base.copy(latitude = 61.0, accuracyMeters = 500.0, timestampMillis = 100_000 + elapsed)
                selector.record(source, coarse, coarse.timestampMillis)
                assertEquals(base, selector.select(coarse.timestampMillis)?.fix)
            }
            assertEquals(130_000L, selector.select(130_001)?.fix?.timestampMillis)
        }
    }

    @Test fun reportedMovementSpeedAcceleratesSwitchingWithoutRewritingTime() {
        val selector = LocationFixSelector()
        val moving = base.copy(speedMetersPerSecond = 20.0)
        selector.record("gps", moving, 100_000)
        selector.select(100_000)
        val newer = base.copy(accuracyMeters = 10.0, timestampMillis = 104_000)
        selector.record("google-fused", newer, 104_000)
        assertEquals(newer, selector.select(104_000)?.fix)
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

    @Test fun reportedFiftyKilometerDisagreementPrefersNetworkDespiteSatelliteConsensus() {
        val selector = LocationFixSelector()
        val satellite = base.copy(latitude = 60.11876669, longitude = 31.38699253,
            accuracyMeters = 5.7, timestampMillis = 100_000, speedMetersPerSecond = 35.42)
        for (source in listOf("gps", "fused", "google-fused")) selector.record(source, satellite, 100_000)
        assertEquals("fused", selector.select(100_000)?.source)
        val network = base.copy(accuracyMeters = 152.0, timestampMillis = 95_000)
        selector.record("network", network, 100_000)
        val recovered = selector.select(100_000)
        assertEquals(SelectedLocationFix("network", network, sourceConflict = true), recovered)
        assertEquals(setOf("gps", "fused", "google-fused"), selector.conflictingSources)
        assertTrue(canReplaceLocationFix(satellite, network, recovered?.sourceConflict == true))
        assertFalse(canReplaceLocationFix(satellite, network))
    }

    @Test fun sourceConflictDoesNotRequireNetworkPositionInsideAnyMap() {
        val selector = LocationFixSelector()
        val satellite = base.copy(longitude = 31.4)
        val network = base.copy(longitude = 32.4, accuracyMeters = 100.0)
        selector.record("gps", satellite, 100_000)
        selector.record("network", network, 100_000)
        assertEquals(network, selector.select(100_000)?.fix)
    }

    @Test fun staleOrPoorlyLocatedNetworkDoesNotQuarantineSatelliteSources() {
        for (network in listOf(base.copy(timestampMillis = 69_999), base.copy(accuracyMeters = 1_000.0))) {
            val selector = LocationFixSelector()
            val satellite = base.copy(longitude = 31.4)
            selector.record("gps", satellite, 100_000)
            selector.record("network", network, 100_000)
            assertEquals(satellite, selector.select(100_000)?.fix)
            assertTrue(selector.conflictingSources.isEmpty())
        }
    }

    @Test fun modestDisagreementAndPossibleTravelDoNotTriggerQuarantine() {
        val selector = LocationFixSelector()
        val network = base.copy(accuracyMeters = 150.0, timestampMillis = 80_000)
        val satellite = base.copy(latitude = base.latitude + 0.02, timestampMillis = 100_000)
        selector.record("network", network, 100_000)
        selector.record("gps", satellite, 100_000)
        assertEquals(satellite, selector.select(100_000)?.fix)
        assertTrue(selector.conflictingSources.isEmpty())
    }

    @Test fun networkExpiryDoesNotRestoreStillConflictingSatelliteCoordinates() {
        val selector = LocationFixSelector()
        val satellite = base.copy(longitude = 31.4)
        selector.record("gps", satellite, 100_000)
        selector.record("network", base.copy(accuracyMeters = 150.0), 100_000)
        assertEquals("network", selector.select(100_000)?.source)
        selector.record("gps", satellite.copy(timestampMillis = 130_001), 130_001)
        assertNull(selector.select(130_001))
        assertEquals(setOf("gps"), selector.conflictingSources)
    }

    @Test fun freshSourceAgreementRestoresOrdinaryAccuracySelection() {
        val selector = LocationFixSelector()
        selector.record("gps", base.copy(longitude = 31.4), 100_000)
        selector.record("network", base.copy(accuracyMeters = 150.0), 100_000)
        selector.select(100_000)
        val recovered = base.copy(timestampMillis = 101_000)
        selector.record("gps", recovered, 101_000)
        assertEquals(SelectedLocationFix("gps", recovered), selector.select(101_000))
        assertTrue(selector.conflictingSources.isEmpty())
    }

    @Test fun distanceHandlesDatelineAndIdenticalPositions() {
        assertEquals(0.0, locationDistanceMeters(base, base))
        val west = base.copy(latitude = 0.0, longitude = 179.999)
        val east = base.copy(latitude = 0.0, longitude = -179.999)
        assertTrue(locationDistanceMeters(west, east) in 220.0..225.0)
    }
}
