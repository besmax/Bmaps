/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationFixSelectionTest {
    private val gps = LocationFix(60.060066, 30.439427, 5.0, 100_000)

    @Test fun worseFallbackDoesNotDisplaceFreshGpsAfterTenSeconds() {
        for (elapsed in listOf(10_000L, 15_000L, 20_000L, 30_000L)) {
            val now = gps.timestampMillis + elapsed
            val fallback = LocationFix(60.1, 30.5, 1_000.0, now)
            assertFalse(shouldAcceptLocationFix(gps, fallback, preserveCurrentAccuracy = true, nowMillis = now))
        }
    }

    @Test fun fallbackRecoversAfterGpsExpires() {
        val now = gps.timestampMillis + 30_001
        val fallback = LocationFix(60.1, 30.5, 1_000.0, now)
        assertTrue(shouldAcceptLocationFix(gps, fallback, preserveCurrentAccuracy = true, nowMillis = now))
    }

    @Test fun betterFallbackAndNewGpsContinueUpdating() {
        val now = gps.timestampMillis + 15_000
        assertTrue(shouldAcceptLocationFix(gps, gps.copy(accuracyMeters = 3.0, timestampMillis = now), true, now))
        assertTrue(shouldAcceptLocationFix(gps, gps.copy(accuracyMeters = 10.0, timestampMillis = now), false, now))
        assertFalse(shouldAcceptLocationFix(gps, gps.copy(accuracyMeters = 3.0, timestampMillis = gps.timestampMillis - 1), true, now))
    }

    @Test fun invalidFixCannotReplaceGpsOrPoisonSelection() {
        val now = gps.timestampMillis + 15_000
        val invalid = gps.copy(latitude = Double.NaN, timestampMillis = now)
        assertFalse(shouldAcceptLocationFix(gps, invalid, false, now))
        assertFalse(shouldAcceptLocationFix(null, invalid, false, now))
    }
}
