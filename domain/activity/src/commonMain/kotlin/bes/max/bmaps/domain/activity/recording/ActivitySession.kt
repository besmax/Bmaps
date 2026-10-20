/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.recording

import bes.max.bmaps.domain.activity.model.ActivityDataAvailability
import bes.max.bmaps.domain.activity.model.ActivityDurations
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityProfile
import bes.max.bmaps.domain.activity.model.ActivityRecord
import bes.max.bmaps.domain.activity.model.ActivityResult
import bes.max.bmaps.domain.activity.model.RouteProgress
import bes.max.bmaps.domain.activity.model.RouteSelection
import bes.max.bmaps.domain.activity.model.TelemetrySample
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationAlert
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationCommand
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationState
import kotlinx.coroutines.flow.Flow

data class ActivityStartRequest(
    val id: String,
    val name: String,
    val profile: ActivityProfile,
    val route: RouteSelection? = null,
    val packageId: String? = null,
)

@kotlinx.serialization.Serializable
data class ActivityCommand(
    val id: String,
    val activityId: String,
    val sessionGeneration: String,
    val expectedControlRevision: Long,
    val action: ActivityAction,
)

data class ActivityLiveState(
    val record: ActivityRecord,
    val distanceMeters: Double,
    val durations: ActivityDurations,
    val position: TelemetrySample? = null,
    val speed: TelemetrySample? = null,
    val heartRate: TelemetrySample? = null,
    val progress: RouteProgress? = null,
    val positionAvailability: ActivityDataAvailability = ActivityDataAvailability.WAITING,
    val heartRateAvailability: ActivityDataAvailability = ActivityDataAvailability.WAITING,
    val failure: ActivityFailure? = null,
)

interface ActivitySession {
    fun initialize()
    val active: Flow<ActivityRecord?>
    val live: Flow<ActivityLiveState?>
    val navigationAlerts: Flow<ActivityNavigationAlert>
    suspend fun navigate(command: ActivityNavigationCommand): ActivityResult<ActivityNavigationState>
    suspend fun start(request: ActivityStartRequest): ActivityResult<ActivityRecord>
    suspend fun apply(command: ActivityCommand): ActivityResult<ActivityRecord>
    suspend fun recover(): ActivityResult<ActivityRecord?>
}
