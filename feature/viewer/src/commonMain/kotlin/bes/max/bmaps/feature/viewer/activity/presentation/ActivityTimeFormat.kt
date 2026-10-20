/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun activityTimeText(epochMillis: Long, timeZone: TimeZone = TimeZone.currentSystemDefault(),
    dateTimeSeparator: String = " "): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(timeZone)
    val time = listOf(local.hour, local.minute, local.second).joinToString(":") { it.toString().padStart(2, '0') }
    val offset = timeZone.offsetAt(instant).toString().let { if (it == "Z") "" else it }
    return "${local.date}$dateTimeSeparator$time UTC$offset"
}
