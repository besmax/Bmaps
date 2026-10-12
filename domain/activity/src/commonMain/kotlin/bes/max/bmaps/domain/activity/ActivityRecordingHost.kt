/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

interface ActivityRecordingHost {
    val interruptions: Flow<Unit>
    suspend fun start(profile: ActivityProfile): ActivityFailure?
    fun stop()
}

@Inject
@SingleIn(AppScope::class)
class PhoneActivityClock : ActivityClock {
    private val continuity = activityIdentity()
    override fun now() = ActivityTime(activityEpochMillis(), activityMonotonicMillis(), continuity)
}

internal expect fun activityEpochMillis(): Long
internal expect fun activityMonotonicMillis(): Long
internal expect fun activityIdentity(): String
