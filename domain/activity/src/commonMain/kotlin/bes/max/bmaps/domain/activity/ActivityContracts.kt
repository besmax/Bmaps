/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import kotlinx.coroutines.flow.Flow

sealed interface ActivityResult<out T> {
    data class Success<T>(val value: T) : ActivityResult<T>
    data class Failure(val reason: ActivityFailure) : ActivityResult<Nothing>
}

sealed interface ActivityFailure {
    data class InvalidInput(val field: String) : ActivityFailure
    data class InvalidTransition(val status: ActivityStatus, val action: ActivityAction) : ActivityFailure
    data object NotFound : ActivityFailure
    data object AnotherActivityActive : ActivityFailure
    data object RevisionConflict : ActivityFailure
    data object StaleSession : ActivityFailure
    data object ForegroundRequired : ActivityFailure
    data object RecordingUnavailable : ActivityFailure
    data object PermissionRequired : ActivityFailure
    data object PreciseLocationRequired : ActivityFailure
    data object LocationDisabled : ActivityFailure
    data object CapacityExceeded : ActivityFailure
    data object StorageUnavailable : ActivityFailure
    data object UnsupportedVersion : ActivityFailure
    data object CorruptData : ActivityFailure
    data object ActivityBusy : ActivityFailure
}

data class ActivityStartRequest(
    val id: String,
    val name: String,
    val profile: ActivityProfile,
    val route: RouteSelection? = null,
)

@kotlinx.serialization.Serializable
data class ActivityCommand(
    val id: String,
    val activityId: String,
    val sessionGeneration: String,
    val expectedControlRevision: Long,
    val action: ActivityAction,
)

data class ActivityCommit(
    val expectedRevision: Long,
    val record: ActivityRecord,
    val samples: List<TelemetrySample> = emptyList(),
    val events: List<ActivityEvent> = emptyList(),
    val appliedCommandId: String? = null,
)

data class ActivityHistoryCursor(val startedAtEpochMillis: Long, val id: String)
data class ActivityHistoryQuery(
    val before: ActivityHistoryCursor? = null,
    val limit: Int = 50,
    val nameContains: String = "",
    val profiles: Set<ActivityProfile> = emptySet(),
    val statuses: Set<ActivityStatus> = emptySet(),
)
data class ActivitySummary(
    val id: String, val name: String, val profile: ActivityProfile, val status: ActivityStatus,
    val revision: Long, val startedAtEpochMillis: Long, val finishedAtEpochMillis: Long?, val routeId: String?,
)
data class ActivityHistoryPage(val items: List<ActivitySummary>, val next: ActivityHistoryCursor?)
data class ActivityRouteSummary(val id: String, val name: String, val revision: Long, val vertexCount: Int, val segmentCount: Int)
data class ActivityRouteQuery(val afterId: String? = null, val limit: Int = 50, val nameContains: String = "")
data class ActivityRoutePage(val items: List<ActivityRouteSummary>, val nextId: String?)
data class TelemetryCursor(val acquiredAtEpochMillis: Long, val sourceId: String, val sampleId: String)
data class TelemetryQuery(val fromEpochMillis: Long, val toEpochMillis: Long, val after: TelemetryCursor? = null, val limit: Int = 256)
data class TelemetryPage(val items: List<TelemetrySample>, val next: TelemetryCursor?)
data class ActivityEventQuery(val afterSequence: Long? = null, val limit: Int = 256)
data class ActivityEventPage(val items: List<ActivityEvent>, val nextSequence: Long?)

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

interface ActivityRepository {
    suspend fun create(record: ActivityRecord, started: ActivityEvent): ActivityResult<ActivityRecord>
    suspend fun get(id: String): ActivityResult<ActivityRecord>
    suspend fun commit(value: ActivityCommit): ActivityResult<ActivityRecord>
    suspend fun history(query: ActivityHistoryQuery): ActivityResult<ActivityHistoryPage>
    suspend fun telemetry(activityId: String, query: TelemetryQuery): ActivityResult<TelemetryPage>
    suspend fun events(activityId: String, query: ActivityEventQuery): ActivityResult<ActivityEventPage>
    suspend fun delete(id: String): ActivityResult<Unit>
    suspend fun reconcile(): ActivityResult<Unit>
    suspend fun appliedCommand(activityId: String, commandId: String): ActivityResult<ActivityRecord?>
    fun observeActive(): Flow<ActivityRecord?>
}

interface ActivityRouteRepository {
    suspend fun get(id: String): ActivityResult<ActivityRoute>
    suspend fun save(route: ActivityRoute, expectedRevision: Long?): ActivityResult<ActivityRoute>
    suspend fun derive(activityId: String, routeId: String): ActivityResult<ActivityRoute?>
    suspend fun delete(id: String): ActivityResult<Unit>
    suspend fun routes(query: ActivityRouteQuery): ActivityResult<ActivityRoutePage>
    suspend fun saveImported(routes: List<ActivityRoute>): ActivityResult<Unit>
}

interface ActivitySession {
    fun initialize()
    val active: Flow<ActivityRecord?>
    val live: Flow<ActivityLiveState?>
    suspend fun start(request: ActivityStartRequest): ActivityResult<ActivityRecord>
    suspend fun apply(command: ActivityCommand): ActivityResult<ActivityRecord>
    suspend fun recover(): ActivityResult<ActivityRecord?>
}
