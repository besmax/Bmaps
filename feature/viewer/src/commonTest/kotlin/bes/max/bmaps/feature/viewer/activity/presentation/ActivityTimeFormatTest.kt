/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ActivityTimeFormatTest {
    @Test fun moscowUsesLocalTimeAndRollsOverTheDate() {
        assertEquals("2026-10-20 01:30:00 UTC+03:00", format("2026-10-19T22:30:00Z", "Europe/Moscow"))
    }

    @Test fun negativeAndFractionalOffsetsUseTheCorrectLocalDateAndTime() {
        assertEquals("2026-10-19 20:30:00 UTC-04:00", format("2026-10-20T00:30:00Z", "America/New_York"))
        assertEquals("2026-10-20 06:15:00 UTC+05:45", format("2026-10-20T00:30:00Z", "Asia/Kathmandu"))
    }

    @Test fun daylightSavingOverlapKeepsTheTwoInstantsDistinct() {
        assertEquals("2026-10-25 02:30:00 UTC+02:00", format("2026-10-25T00:30:00Z", "Europe/Berlin"))
        assertEquals("2026-10-25 02:30:00 UTC+01:00", format("2026-10-25T01:30:00Z", "Europe/Berlin"))
    }

    @Test fun utcAndMultilineLabelsUseTheSameFormatter() {
        val epoch = Instant.parse("2026-10-20T12:03:04Z").toEpochMilliseconds()
        assertEquals("2026-10-20 12:03:04 UTC", activityTimeText(epoch, TimeZone.UTC))
        assertEquals("2026-10-20\n15:03:04 UTC+03:00",
            activityTimeText(epoch, TimeZone.of("Europe/Moscow"), "\n"))
    }

    private fun format(instant: String, zone: String) =
        activityTimeText(Instant.parse(instant).toEpochMilliseconds(), TimeZone.of(zone))
}
