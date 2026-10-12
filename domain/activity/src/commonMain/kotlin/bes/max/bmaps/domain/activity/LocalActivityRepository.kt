/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.storage.ActivitySpatialStores
import bes.max.bmaps.core.storage.StoredActivityCheckpoint
import bes.max.bmaps.core.storage.StoredActivityEvent
import bes.max.bmaps.core.storage.StoredTelemetry
import bes.max.bmaps.core.storage.StoredNavigationSnapshot
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

@Inject
@SingleIn(AppScope::class)
class LocalActivityRepository(private val store: LocalActivityStore) : ActivityRepository {
    override suspend fun create(record: ActivityRecord, started: ActivityEvent): ActivityResult<ActivityRecord> = store.operation {
        val ownedRecord = record.owned()
        activityRequire(ActivityValidation.record(ownedRecord))
        validateEvent(started)
        if (ownedRecord.status != ActivityStatus.RECORDING || ownedRecord.revision != 0L || ownedRecord.controlRevision != 0L ||
            started.kind != ActivityEventKind.STARTED || started.sequence != 1L || started.time != ownedRecord.startedAt ||
            ownedRecord.updatedAt != ownedRecord.startedAt || ownedRecord.derivedRouteId != null) activityFail(ActivityFailure.InvalidInput("start"))
        val metadata = store.stored(ownedRecord)
        val snapshot = ownedRecord.route?.let { store.json.encodeToString(it) }
        if (snapshot != null && snapshot.encodeToByteArray().size > ActivityLimits.MAX_ROUTE_STORAGE_BYTES) activityFail(ActivityFailure.CapacityExceeded)
        val event = started.stored()
        requireCapacity((metadata.payload.length.toLong() + (snapshot?.encodeToByteArray()?.size ?: 0) + event.payload.length) * 2 + 1_048_576)
        val saved = activities.create(metadata, snapshot, event, activityDigest(metadata.payload + "\n" + snapshot + "\n" + event.payload))
        store.project(saved)
        with(store) { read(saved) }.owned()
    }

    override suspend fun get(id: String): ActivityResult<ActivityRecord> = store.operation { validateActivityId(id); record(id).owned() }

    override suspend fun commit(value: ActivityCommit): ActivityResult<ActivityRecord> = store.operation {
        val request = value.copy(record = value.record.owned(), samples = value.samples.toList(), events = value.events.toList(), navigationSnapshot = value.navigationSnapshot?.owned())
        validateActivityId(request.record.id)
        val previous = record(request.record.id)
        activityRequire(if (request.record.route == previous.route) ActivityValidation.recordMetadata(request.record) else ActivityValidation.record(request.record))
        request.appliedCommandId?.let(::validateActivityId)
        if (request.samples.size > ActivityLimits.MAX_WRITE_SAMPLES || request.events.size > ActivityLimits.MAX_WRITE_EVENTS) activityFail(ActivityFailure.InvalidInput("batch"))
        request.samples.forEach { activityRequire(ActivityValidation.sample(it)) }
        request.events.forEach(::validateEvent)
        val metadata = store.stored(request.record)
        val samples = request.samples.map { it.stored() }
        val events = request.events.map { it.stored() }
        val bytes = (samples.map { it.payload } + events.map { it.payload } + metadata.payload).sumOf { it.encodeToByteArray().size.toLong() }
        if (bytes > ActivityLimits.MAX_WRITE_BYTES) activityFail(ActivityFailure.InvalidInput("batchBytes"))
        val navigationSnapshot = request.navigationSnapshot?.let {
            activityRequire(ActivityValidation.route(it))
            store.json.encodeToString(it).also { encoded -> if (encoded.encodeToByteArray().size > ActivityLimits.MAX_ROUTE_STORAGE_BYTES) activityFail(ActivityFailure.CapacityExceeded) }
        }
        val fingerprint = activityDigest("${request.expectedRevision}\n${metadata.payload}\n" + events.joinToString("\n") { it.payload } + "\n" + samples.joinToString("\n") { it.payload } + "\n" + navigationSnapshot?.let(::activityDigest))
        val duplicate = request.appliedCommandId?.let { activities.commandResult(request.record.id, it) }
        if (duplicate == null) {
            validateChange(previous, request)
            if (request.record.derivedRouteId != null && request.record.derivedRouteId != previous.derivedRouteId &&
                routes.derived(previous.id) != request.record.derivedRouteId) activityFail(ActivityFailure.InvalidInput("derivedRouteId"))
            val nav = request.record.navigation
            if (nav != null) {
                val route = request.navigationSnapshot ?: with(store) { navigationRoute(request.record) } ?: activityFail(ActivityFailure.InvalidInput("navigationRoute"))
                activityRequire(ActivityValidation.navigation(nav, route))
                if (nav.mode == ActivityNavigationMode.PLANNED_ROUTE && (nav.direction != request.record.route?.direction || nav.origin != request.record.route?.start)) activityFail(ActivityFailure.InvalidInput("navigationSelection"))
            }
            if (request.navigationSnapshot != null && (nav?.mode != ActivityNavigationMode.RECORDED_RETURN || request.appliedCommandId == null || request.record.lastNavigationCommand?.action != ActivityNavigationAction.RETURN_ALONG_RECORDING)) activityFail(ActivityFailure.InvalidInput("navigationSnapshot"))
            val checkpoint = nextCheckpoint(previous, request)
            requireCapacity((bytes + (navigationSnapshot?.encodeToByteArray()?.size ?: 0)) * 2 + 1_048_576)
            val saved = activities.commit(metadata, request.expectedRevision, events, samples, checkpoint,
                request.record.status != ActivityStatus.COMPLETED, request.appliedCommandId, fingerprint,
                request.navigationSnapshot?.let { StoredNavigationSnapshot(it.id, checkNotNull(navigationSnapshot)) })
            store.project(saved)
            with(store) { read(saved) }.owned()
        } else {
            val saved = activities.commit(metadata, request.expectedRevision, events, samples, activities.checkpoint(request.record.id),
                request.record.status != ActivityStatus.COMPLETED, request.appliedCommandId, fingerprint,
                request.navigationSnapshot?.let { StoredNavigationSnapshot(it.id, checkNotNull(navigationSnapshot)) })
            with(store) { read(saved) }.owned()
        }
    }

    override suspend fun history(query: ActivityHistoryQuery): ActivityResult<ActivityHistoryPage> = store.operation {
        activityRequire(ActivityValidation.history(query))
        val cursor = query.before
        val rows = store.records.activities(query.nameContains, query.profiles.map { it.name }.sorted(), query.profiles.isEmpty(),
            query.statuses.map { it.name }.sorted(), query.statuses.isEmpty(), cursor == null,
            cursor?.startedAtEpochMillis ?: 0, cursor?.id ?: "", query.limit + 1)
        val page = rows.take(query.limit)
        ActivityHistoryPage(page.map { ActivitySummary(it.id, it.name, ActivityProfile.valueOf(it.profile), ActivityStatus.valueOf(it.status),
            it.revision, it.startedAtEpochMillis, it.finishedAtEpochMillis, it.routeId) },
            if (rows.size > query.limit) page.last().let { ActivityHistoryCursor(it.startedAtEpochMillis, it.id) } else null)
    }

    override suspend fun telemetry(activityId: String, query: TelemetryQuery): ActivityResult<TelemetryPage> = store.operation {
        validateActivityId(activityId)
        activityRequire(ActivityValidation.telemetry(query))
        if (activities.get(activityId) == null) activityFail(ActivityFailure.NotFound)
        val after = query.after
        val rows = activities.samples(activityId, query.fromEpochMillis, query.toEpochMillis, after?.acquiredAtEpochMillis ?: 0,
            after?.sourceId ?: "", after?.sampleId ?: "", after == null, query.limit + 1)
        val page = rows.take(query.limit)
        TelemetryPage(page.map { store.decode<TelemetrySample>(it.payload).also { sample ->
            if (ActivityValidation.sample(sample) != null) activityFail(ActivityFailure.CorruptData)
        } }, if (rows.size > query.limit) page.last().let { TelemetryCursor(it.acquiredAt, it.sourceId, it.id) } else null)
    }

    override suspend fun navigationRoute(activityId: String): ActivityResult<ActivityRoute?> = store.operation {
        validateActivityId(activityId)
        with(store) { navigationRoute(record(activityId)) }?.owned()
    }

    override suspend fun positions(activityId: String, query: ActivityPositionQuery): ActivityResult<ActivityPositionPage> = store.operation {
        validateActivityId(activityId)
        if (query.afterSequence < 0 || query.limit !in 1..ActivityLimits.MAX_PAGE_SIZE) activityFail(ActivityFailure.InvalidInput("positionQuery"))
        if (activities.get(activityId) == null) activityFail(ActivityFailure.NotFound)
        val rows = activities.positions(activityId, query.afterSequence, query.limit + 1)
        val page = rows.take(query.limit)
        ActivityPositionPage(page.map { store.decode<TelemetrySample>(it.payload).also { sample ->
            if (ActivityValidation.sample(sample) != null || sample.measurement !is PositionMeasurement) activityFail(ActivityFailure.CorruptData)
        } }, if (rows.size > query.limit) page.last().sequence else null)
    }

    override suspend fun events(activityId: String, query: ActivityEventQuery): ActivityResult<ActivityEventPage> = store.operation {
        validateActivityId(activityId)
        if (query.limit !in 1..ActivityLimits.MAX_PAGE_SIZE || (query.afterSequence ?: 0) < 0) activityFail(ActivityFailure.InvalidInput("eventQuery"))
        if (activities.get(activityId) == null) activityFail(ActivityFailure.NotFound)
        val rows = activities.events(activityId, query.afterSequence ?: 0, query.limit + 1)
        val page = rows.take(query.limit)
        ActivityEventPage(page.map { store.decode<ActivityEvent>(it.payload) }, if (rows.size > query.limit) page.last().sequence else null)
    }

    override suspend fun delete(id: String): ActivityResult<Unit> = store.operation {
        validateActivityId(id)
        val record = record(id)
        if (record.status in setOf(ActivityStatus.RECORDING, ActivityStatus.PAUSED, ActivityStatus.FINALIZING)) activityFail(ActivityFailure.ActivityBusy)
        requireCapacity(1_048_576)
        activities.delete(id)
        store.records.deleteActivity(id)
    }

    override suspend fun reconcile(): ActivityResult<Unit> = store.reconcile()

    override suspend fun appliedCommand(activityId: String, commandId: String): ActivityResult<ActivityRecord?> = store.operation {
        validateActivityId(activityId); validateActivityId(commandId)
        val existing = record(activityId)
        activities.commandResult(activityId, commandId)?.let { store.decode<ActivityRecord>(it).copy(route = existing.route).owned() }
    }

    override fun observeActive(): Flow<ActivityRecord?> = flow {
        when (val initialized = store.operation { Unit }) {
            is ActivityResult.Failure -> throw ActivityStorageFailure(initialized.reason)
            is ActivityResult.Success -> emitAll(store.active.map { it?.owned() })
        }
    }

    private fun ActivitySpatialStores.record(id: String): ActivityRecord = with(store) {
        read(activities.get(id) ?: activityFail(ActivityFailure.NotFound))
    }

    private fun ActivitySpatialStores.nextCheckpoint(previous: ActivityRecord, value: ActivityCommit): StoredActivityCheckpoint {
        var checkpoint = activities.checkpoint(previous.id)
        val introduced = mutableSetOf<String>()
        val seen = mutableMapOf<Pair<String, String>, TelemetrySample>()
        for (sample in value.samples) {
            val duplicate = seen.put(sample.source.id to sample.id, sample)
            if (duplicate != null) {
                if (duplicate != sample) activityFail(ActivityFailure.InvalidInput("sampleIdentity"))
                continue
            }
            if (activities.sample(previous.id, sample.source.id, sample.id) != null) continue
            if (sample.phase != MeasurementPhase.RECORDING) activityFail(ActivityFailure.InvalidInput("phase"))
            val time = sample.acquiredAt.epochMillis
            val storedPhase = activities.lifecycleAt(previous.id, time, sample.acquiredAt.monotonicMillis, sample.acquiredAt.continuityId)
            val pendingPhase = value.events.filter { beforeOrAt(it.time, sample.acquiredAt) && it.kind in LIFECYCLE_EVENTS }.maxByOrNull { it.sequence }
            val phase = if (pendingPhase != null && pendingPhase.sequence > (storedPhase?.sequence ?: 0)) pendingPhase.kind.name else storedPhase?.kind
            if (phase != "STARTED" && phase != "RESUMED") activityFail(ActivityFailure.InvalidInput("sampleInterval"))
            if (sample.measurement is PositionMeasurement) {
                if (value.events.any { it.kind in SEGMENT_BREAK_EVENTS && beforeOrAt(it.time, sample.acquiredAt) &&
                        atOrAfterPosition(it.time, checkpoint) }) checkpoint = checkpoint.copy(segmentBreakRequired = true)
                val sameClock = checkpoint.positionClockId == sample.acquiredAt.continuityId &&
                    checkpoint.positionMonotonic != null && sample.acquiredAt.monotonicMillis != null
                val delta = if (sameClock) sample.acquiredAt.monotonicMillis!! - checkpoint.positionMonotonic!!
                    else checkpoint.positionTime?.let { time - it }
                if (previous.status != ActivityStatus.RECORDING || sample.measurement.sourceConflict ||
                    delta?.let { it <= 0 && (sameClock || sample.pathSegmentId == checkpoint.segmentId || !checkpoint.segmentBreakRequired) } == true) activityFail(ActivityFailure.InvalidInput("positionOrder"))
                val segment = checkNotNull(sample.pathSegmentId)
                if (segment == checkpoint.segmentId && (checkpoint.segmentBreakRequired ||
                    delta?.let { it > ActivityQualityPolicy().pathGapMillis } == true)) activityFail(ActivityFailure.InvalidInput("segmentGap"))
                if (segment != checkpoint.segmentId && (segment in introduced || activities.hasSegment(previous.id, segment))) activityFail(ActivityFailure.InvalidInput("segmentReuse"))
                if (segment != checkpoint.segmentId) introduced += segment
                checkpoint = checkpoint.copy(positionTime = time, segmentId = segment, segmentBreakRequired = false,
                    positionMonotonic = sample.acquiredAt.monotonicMillis, positionClockId = sample.acquiredAt.continuityId)
            }
        }
        if (value.events.any { it.kind in SEGMENT_BREAK_EVENTS && atOrAfterPosition(it.time, checkpoint) }) {
            checkpoint = checkpoint.copy(segmentBreakRequired = true)
        }
        return checkpoint.copy(eventSequence = value.events.lastOrNull()?.sequence ?: checkpoint.eventSequence)
    }

    private fun validateChange(previous: ActivityRecord, value: ActivityCommit) {
        val next = value.record
        if (previous.revision != value.expectedRevision || value.expectedRevision == Long.MAX_VALUE || next.revision != previous.revision + 1) activityFail(ActivityFailure.RevisionConflict)
        if (next.profile != previous.profile || next.startedAt != previous.startedAt || next.route != previous.route) activityFail(ActivityFailure.InvalidInput("immutableActivity"))
        if (next.updatedAt.continuityId == previous.updatedAt.continuityId &&
            next.updatedAt.monotonicMillis!! < previous.updatedAt.monotonicMillis!!) activityFail(ActivityFailure.InvalidInput("updateTime"))
        val changed = next.status != previous.status
        val action = when (next.status) {
            ActivityStatus.RECORDING -> ActivityAction.RESUME
            ActivityStatus.PAUSED -> ActivityAction.PAUSE
            ActivityStatus.INTERRUPTED -> ActivityAction.INTERRUPT
            ActivityStatus.FINALIZING -> ActivityAction.FINISH
            ActivityStatus.COMPLETED -> ActivityAction.COMPLETE
        }
        if (changed && ActivityLifecycle.next(previous.status, action) != next.status) activityFail(ActivityFailure.InvalidTransition(previous.status, action))
        if (previous.controlRevision == Long.MAX_VALUE || next.controlRevision != previous.controlRevision + (if (changed) 1 else 0)) activityFail(ActivityFailure.RevisionConflict)
        val recovered = previous.status == ActivityStatus.INTERRUPTED && next.status == ActivityStatus.RECORDING
        if ((next.sessionGeneration != previous.sessionGeneration) != recovered) activityFail(ActivityFailure.StaleSession)
        if (previous.finishedAt != null && next.finishedAt != previous.finishedAt || previous.derivedRouteId != null && next.derivedRouteId != previous.derivedRouteId) activityFail(ActivityFailure.InvalidInput("finishBoundary"))
        val oldNav = previous.navigation
        val nextNav = next.navigation
        val navigationCommandChanged = next.lastNavigationCommand != previous.lastNavigationCommand
        if (navigationCommandChanged) {
            val command = next.lastNavigationCommand ?: activityFail(ActivityFailure.InvalidInput("navigationCommand"))
            if (command.id != value.appliedCommandId || command.sessionGeneration != previous.sessionGeneration ||
                command.expectedNavigationRevision != (oldNav?.revision ?: 0) || nextNav == null || nextNav.revision != (oldNav?.revision ?: 0) + 1)
                activityFail(ActivityFailure.RevisionConflict)
        } else if (oldNav != null) {
            if (nextNav == null || nextNav.revision != oldNav.revision || nextNav.mode != oldNav.mode || nextNav.routeId != oldNav.routeId ||
                nextNav.routeRevision != oldNav.routeRevision || nextNav.direction != oldNav.direction || nextNav.origin != oldNav.origin || nextNav.alertsEnabled != oldNav.alertsEnabled)
                activityFail(ActivityFailure.InvalidInput("navigationControl"))
            if (nextNav.checkpoint != oldNav.checkpoint && value.samples.none { it.measurement is PositionMeasurement }) activityFail(ActivityFailure.InvalidInput("navigationCheckpoint"))
        } else if (nextNav != null && (nextNav.mode != ActivityNavigationMode.PLANNED_ROUTE || nextNav.revision != 0L)) activityFail(ActivityFailure.InvalidInput("navigationControl"))
        val lifecycle = value.events.filter { it.kind in LIFECYCLE_EVENTS }
        if (changed) {
            val expected = when (next.status) {
                ActivityStatus.RECORDING -> ActivityEventKind.RESUMED
                ActivityStatus.PAUSED -> ActivityEventKind.PAUSED
                ActivityStatus.INTERRUPTED -> ActivityEventKind.INTERRUPTED
                ActivityStatus.FINALIZING -> ActivityEventKind.FINISH_REQUESTED
                ActivityStatus.COMPLETED -> ActivityEventKind.COMPLETED
            }
            if (lifecycle.size != 1 || lifecycle.single().kind != expected || lifecycle.single().time != next.updatedAt) activityFail(ActivityFailure.InvalidInput("lifecycleEvent"))
            if (next.status == ActivityStatus.FINALIZING && next.finishedAt != next.updatedAt) activityFail(ActivityFailure.InvalidInput("finishTime"))
        } else if (lifecycle.isNotEmpty()) activityFail(ActivityFailure.InvalidInput("lifecycleEvent"))
    }

    private fun validateEvent(value: ActivityEvent) {
        if (!ActivityValidation.id(value.id) || value.sequence <= 0 || !ActivityValidation.time(value.time) ||
            value.time.monotonicMillis == null || value.relatedId?.let { !ActivityValidation.id(it) } == true || value.navigationRouteId?.let { !ActivityValidation.id(it) } == true) activityFail(ActivityFailure.InvalidInput("event"))
    }

    private fun beforeOrAt(event: ActivityTime, sample: ActivityTime): Boolean =
        if (event.continuityId == sample.continuityId && event.monotonicMillis != null && sample.monotonicMillis != null)
            event.monotonicMillis <= sample.monotonicMillis else event.epochMillis <= sample.epochMillis

    private fun atOrAfterPosition(event: ActivityTime, checkpoint: StoredActivityCheckpoint): Boolean {
        val positionTime = checkpoint.positionTime ?: return true
        val positionMonotonic = checkpoint.positionMonotonic
        return if (event.continuityId == checkpoint.positionClockId && event.monotonicMillis != null && positionMonotonic != null)
            event.monotonicMillis >= positionMonotonic else event.epochMillis >= positionTime
    }

    private fun ActivityEvent.stored() = StoredActivityEvent(id, sequence, time.epochMillis, store.json.encodeToString(this), kind.name,
        time.monotonicMillis, time.continuityId)
    private fun TelemetrySample.stored() = StoredTelemetry(id, source.id, acquiredAt.epochMillis, when (measurement) {
        is PositionMeasurement -> "position"
        is SpeedMeasurement -> "speed"
        is HeartRateMeasurement -> "heart_rate"
        is ElevationMeasurement -> "elevation"
        is CourseMeasurement -> "course"
    }, pathSegmentId, store.json.encodeToString(this))

    private companion object {
        val SEGMENT_BREAK_EVENTS = setOf(ActivityEventKind.PAUSED, ActivityEventKind.INTERRUPTED, ActivityEventKind.POSITION_LOST)
        val LIFECYCLE_EVENTS = setOf(ActivityEventKind.STARTED, ActivityEventKind.PAUSED, ActivityEventKind.RESUMED,
            ActivityEventKind.INTERRUPTED, ActivityEventKind.FINISH_REQUESTED, ActivityEventKind.COMPLETED)
    }
}
