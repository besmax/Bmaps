/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.recording

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.location.*
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.storage.PackageUseGuard
import bes.max.bmaps.domain.activity.model.ActivityClock
import bes.max.bmaps.domain.activity.model.ActivityDataAvailability
import bes.max.bmaps.domain.activity.model.ActivityEvent
import bes.max.bmaps.domain.activity.model.ActivityEventKind
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityLimits
import bes.max.bmaps.domain.activity.model.ActivityMeasurement
import bes.max.bmaps.domain.activity.model.ActivityProfile
import bes.max.bmaps.domain.activity.model.ActivityQualityPolicy
import bes.max.bmaps.domain.activity.model.ActivityRecord
import bes.max.bmaps.domain.activity.model.ActivityResult
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.ActivityStatistics
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.ActivityTime
import bes.max.bmaps.domain.activity.model.ActivityValidation
import bes.max.bmaps.domain.activity.model.CourseMeasurement
import bes.max.bmaps.domain.activity.model.ElevationMeasurement
import bes.max.bmaps.domain.activity.model.MeasurementPhase
import bes.max.bmaps.domain.activity.model.MeasurementSource
import bes.max.bmaps.domain.activity.model.MeasurementSourceKind
import bes.max.bmaps.domain.activity.model.PositionMeasurement
import bes.max.bmaps.domain.activity.model.RouteDirection
import bes.max.bmaps.domain.activity.model.RoutePosition
import bes.max.bmaps.domain.activity.model.SpeedMeasurement
import bes.max.bmaps.domain.activity.model.TelemetrySample
import bes.max.bmaps.domain.activity.model.routePackageId
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationAction
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationAlert
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationCommand
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationMode
import bes.max.bmaps.domain.activity.navigation.ActivityNavigationState
import bes.max.bmaps.domain.activity.navigation.RecordedReturnRoute
import bes.max.bmaps.domain.activity.navigation.RouteNavigationIndex
import bes.max.bmaps.domain.activity.navigation.RouteNavigationPolicy
import bes.max.bmaps.domain.activity.navigation.RouteNavigator
import bes.max.bmaps.domain.activity.repository.ActivityCommit
import bes.max.bmaps.domain.activity.repository.ActivityEventQuery
import bes.max.bmaps.domain.activity.repository.ActivityRepository
import bes.max.bmaps.domain.activity.repository.ActivityRouteQuery
import bes.max.bmaps.domain.activity.repository.ActivityRouteRepository
import bes.max.bmaps.domain.activity.repository.activityDigest
import bes.max.bmaps.domain.activity.repository.owned
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Inject
@SingleIn(AppScope::class)
class PhoneActivitySession(
    private val repository: ActivityRepository,
    private val routes: ActivityRouteRepository,
    private val locations: SharedLocationService,
    private val host: ActivityRecordingHost,
    private val clock: ActivityClock,
    private val packageUse: PackageUseGuard,
) : ActivitySession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var navigator: RouteNavigator? = null
    private val pendingNavigationAlerts = mutableListOf<ActivityNavigationAlert>()
    private val mutableNavigationAlerts = MutableSharedFlow<ActivityNavigationAlert>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val navigationAlerts = mutableNavigationAlerts.asSharedFlow()
    private val mutableActive = MutableStateFlow<ActivityRecord?>(null)
    private val mutableLive = MutableStateFlow<ActivityLiveState?>(null)
    override val active = mutableActive.asStateFlow()
    override val live = mutableLive.asStateFlow()
    private var initialized = false
    private var recovered = false
    private var current: ActivityRecord? = null
    private var locationJob: Job? = null
    private var ticker: Job? = null
    private var eventSequence = 0L
    private var segmentId: String? = null
    private var lastPositionTime: ActivityTime? = null
    private var positionBarrier: ActivityTime? = null
    private var recordingSince: ActivityTime? = null
    private var position: TelemetrySample? = null
    private var speed: TelemetrySample? = null
    private var availability = ActivityDataAvailability.WAITING
    private var failure: ActivityFailure? = null
    private var movement: ActivityMovement? = null
    private var pendingStatistics = ActivityStatistics()
    private val samples = mutableListOf<TelemetrySample>()
    private val events = mutableListOf<ActivityEvent>()
    private var sampleBytes = 0
    private val json = Json { encodeDefaults = true }
    private val policy = ActivityQualityPolicy()

    override fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            host.interruptions.collect { mutex.withLock { interrupt(clock.now()) } }
        }
        scope.launch { recover() }
    }

    private suspend fun <T> owned(block: suspend () -> ActivityResult<T>): ActivityResult<T> =
        scope.async { mutex.withLock { block() } }.await()

    override suspend fun start(request: ActivityStartRequest): ActivityResult<ActivityRecord> = owned {
        val input = request.copy(route = request.route?.let { it.copy(snapshot = it.snapshot.owned()) })
        ActivityValidation.start(input)?.let { return@owned ActivityResult.Failure(ActivityFailure.InvalidInput(it)) }
        if (!recovered) {
            val recovery = recoverLocked()
            if (recovery is ActivityResult.Failure) return@owned recovery
        }
        if (current != null) return@owned ActivityResult.Failure(ActivityFailure.AnotherActivityActive)
        val sourcePackage = input.packageId ?: input.route?.snapshot?.id?.let(::routePackageId)
        if (sourcePackage != null) {
            if (!packageUse.retain(sourcePackage)) return@owned ActivityResult.Failure(ActivityFailure.AnotherActivityActive)
            val present = routes.routes(ActivityRouteQuery(packageId = sourcePackage, limit = 1))
            if (present is ActivityResult.Failure) { packageUse.release(); return@owned present }
        }
        val guidance = input.route?.let { selection ->
            when (val result = makeNavigator(selection.snapshot, ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE,
                selection.snapshot.id, selection.snapshot.revision, selection.direction, selection.start, selection.start), input.profile)) {
                is ActivityResult.Failure -> { packageUse.release(); return@owned result }
                is ActivityResult.Success -> result.value
            }
        }
        host.start(input.profile)?.let { host.stop(); packageUse.release(); return@owned ActivityResult.Failure(it) }
        val now = clock.now()
        val record = ActivityRecord(input.id, 0, activityIdentity(), input.name, input.profile, ActivityStatus.RECORDING, now, now, input.route, navigation = guidance?.state, packageId = sourcePackage).owned()
        val result = repository.create(record, ActivityEvent(activityIdentity(), 1, ActivityEventKind.STARTED, now))
        if (result is ActivityResult.Success) {
            current = result.value
            navigator = guidance
            eventSequence = 1
            failure = null
            pendingStatistics = result.value.statistics
            movement = ActivityMovement(record.profile)
            beginLocations(now)
            beginTicker()
            publish(now)
        } else { host.stop(); packageUse.release() }
        result
    }

    override suspend fun recover(): ActivityResult<ActivityRecord?> = owned { recoverLocked() }

    private suspend fun recoverLocked(): ActivityResult<ActivityRecord?> {
        if (recovered && failure == null) return ActivityResult.Success(current?.owned())
        stopLocations()
        host.stop()
        val reconciliation = repository.reconcile()
        if (reconciliation is ActivityResult.Failure) return reconciliation
        val found = try { repository.observeActive().first() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return ActivityResult.Failure(ActivityFailure.StorageUnavailable) }
        current = found
        if (found?.packageId != null) packageUse.retain(found.packageId) else packageUse.release()
        recovered = true
        failure = null
        samples.clear(); events.clear(); pendingNavigationAlerts.clear(); sampleBytes = 0
        navigator = null
        segmentId = null; lastPositionTime = null; position = null; speed = null
        availability = ActivityDataAvailability.UNAVAILABLE
        if (found == null) { publish(clock.now()); return ActivityResult.Success(null) }
        movement = ActivityMovement(found.profile)
        pendingStatistics = found.statistics
        val navigationRoute = repository.navigationRoute(found.id)
        if (navigationRoute is ActivityResult.Failure) { fail(navigationRoute.reason); return navigationRoute }
        (navigationRoute as ActivityResult.Success<ActivityRoute?>).value?.let { route ->
            val selected = found.route
            val state = found.navigation ?: selected?.let { ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE, route.id, route.revision, it.direction, it.start, it.start) }
                ?: return@let
            when (val made = makeNavigator(route, state, found.profile)) {
                is ActivityResult.Failure -> { fail(made.reason); return made }
                is ActivityResult.Success -> navigator = made.value
            }
        }
        var cursor: Long? = null
        eventSequence = 0
        do {
            when (val page = repository.events(found.id, ActivityEventQuery(cursor, 500))) {
                is ActivityResult.Failure -> { fail(page.reason); return page }
                is ActivityResult.Success -> {
                    page.value.items.filter { it.kind == ActivityEventKind.CONTROL_POINT && it.navigationRouteId == navigator?.state?.routeId }
                        .forEach { event -> event.relatedId?.let { navigator?.restoreControlPoint(it) } }
                    eventSequence = page.value.items.lastOrNull()?.sequence ?: eventSequence
                    cursor = page.value.nextSequence
                }
            }
        } while (cursor != null)
        if (found.status == ActivityStatus.RECORDING || found.status == ActivityStatus.PAUSED) {
            // The last durable checkpoint bounds owned time; downtime must not become recorded motion.
            val interrupted = transition(ActivityAction.INTERRUPT, found.updatedAt)
            if (interrupted is ActivityResult.Failure) return interrupted
        }
        if (current?.status == ActivityStatus.FINALIZING) {
            val completed = finalizeLocked()
            if (completed is ActivityResult.Failure) return completed
        }
        beginTicker()
        if (current != null) publish(clock.now())
        return ActivityResult.Success(current?.owned())
    }

    private suspend fun makeNavigator(route: ActivityRoute, state: ActivityNavigationState, profile: ActivityProfile): ActivityResult<RouteNavigator> {
        ActivityValidation.navigation(state, route)?.let { return ActivityResult.Failure(ActivityFailure.InvalidInput(it)) }
        return try {
            ActivityResult.Success(withContext(Dispatchers.Default) { RouteNavigator(RouteNavigationIndex(route), state, RouteNavigationPolicy.forProfile(profile)) })
        } catch (_: IllegalArgumentException) { ActivityResult.Failure(ActivityFailure.InvalidInput("navigationGeometry")) }
    }

    private suspend fun restoreControls(activityId: String, guidance: RouteNavigator): ActivityResult<Unit> {
        var after: Long? = null
        do {
            when (val page = repository.events(activityId, ActivityEventQuery(after, 500))) {
                is ActivityResult.Failure -> return page
                is ActivityResult.Success -> {
                    page.value.items.filter { it.kind == ActivityEventKind.CONTROL_POINT && it.navigationRouteId == guidance.state.routeId }
                        .forEach { it.relatedId?.let(guidance::restoreControlPoint) }
                    val next = page.value.nextSequence
                    if (next != null && next <= (after ?: 0)) return ActivityResult.Failure(ActivityFailure.CorruptData)
                    after = next
                }
            }
        } while (after != null)
        return ActivityResult.Success(Unit)
    }

    override suspend fun navigate(command: ActivityNavigationCommand): ActivityResult<ActivityNavigationState> = owned {
        ActivityValidation.navigationCommand(command)?.let { return@owned ActivityResult.Failure(ActivityFailure.InvalidInput(it)) }
        when (val duplicate = repository.appliedCommand(command.activityId, command.id)) {
            is ActivityResult.Failure -> return@owned duplicate
            is ActivityResult.Success -> duplicate.value?.let { saved ->
                return@owned if (saved.lastNavigationCommand == command && saved.navigation != null) ActivityResult.Success(saved.navigation)
                    else ActivityResult.Failure(ActivityFailure.InvalidInput("commandIdentity"))
            }
        }
        val record = current ?: return@owned ActivityResult.Failure(ActivityFailure.NotFound)
        if (record.id != command.activityId || record.sessionGeneration != command.sessionGeneration) return@owned ActivityResult.Failure(ActivityFailure.StaleSession)
        if (failure != null) return@owned ActivityResult.Failure(failure!!)
        if (record.status == ActivityStatus.FINALIZING) return@owned ActivityResult.Failure(ActivityFailure.ActivityBusy)
        val old = navigator?.state
        if (command.expectedNavigationRevision != (old?.revision ?: 0) || command.expectedNavigationRevision == Long.MAX_VALUE) return@owned ActivityResult.Failure(ActivityFailure.RevisionConflict)
        if (command.action == ActivityNavigationAction.CORRECT_POSITION) {
            val route = repository.navigationRoute(record.id)
            if (route is ActivityResult.Failure) return@owned route
            val geometry = (route as ActivityResult.Success<ActivityRoute?>).value ?: return@owned ActivityResult.Failure(ActivityFailure.NotFound)
            if (!ActivityValidation.validRoutePosition(geometry, checkNotNull(command.position))) return@owned ActivityResult.Failure(ActivityFailure.InvalidInput("navigationPosition"))
        }
        pendingNavigationAlerts.clear()
        if (flush(clock.now()) is ActivityResult.Failure) return@owned ActivityResult.Failure(checkNotNull(failure))
        var frozen: ActivityRoute? = null
        var replacement: RouteNavigator? = null
        val nextRevision = command.expectedNavigationRevision + 1
        val kind: ActivityEventKind
        val next = when (command.action) {
            ActivityNavigationAction.RETURN_ALONG_RECORDING -> {
                val built = RecordedReturnRoute.build(current!!, "return-${activityDigest(record.id + "|" + command.id)}", repository)
                if (built is ActivityResult.Failure) return@owned built
                val route = (built as ActivityResult.Success<ActivityRoute>).value
                frozen = route
                val start = RoutePosition(route.segments.lastIndex, route.segments.last().vertices.lastIndex - 1, 1.0)
                kind = ActivityEventKind.RETURN_STARTED
                val state = ActivityNavigationState(ActivityNavigationMode.RECORDED_RETURN, route.id, route.revision, RouteDirection.REVERSE, start, start, nextRevision, old?.alertsEnabled ?: true)
                when (val builtNavigator = makeNavigator(route, state, record.profile)) {
                    is ActivityResult.Failure -> return@owned builtNavigator
                    is ActivityResult.Success -> replacement = builtNavigator.value
                }
                state
            }
            ActivityNavigationAction.FOLLOW_PLANNED -> {
                val selection = record.route ?: return@owned ActivityResult.Failure(ActivityFailure.NotFound)
                kind = ActivityEventKind.PLANNED_GUIDANCE_RESTORED
                val state = ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE, selection.snapshot.id, selection.snapshot.revision,
                    selection.direction, selection.start, selection.start, nextRevision, old?.alertsEnabled ?: true)
                when (val builtNavigator = makeNavigator(selection.snapshot, state, record.profile)) {
                    is ActivityResult.Failure -> return@owned builtNavigator
                    is ActivityResult.Success -> replacement = builtNavigator.value
                }
                state
            }
            ActivityNavigationAction.CORRECT_POSITION -> {
                if (old == null) return@owned ActivityResult.Failure(ActivityFailure.NotFound)
                kind = ActivityEventKind.NAVIGATION_CORRECTED
                old.copy(checkpoint = checkNotNull(command.position), revision = nextRevision, deviated = false, gapAnnouncedSegmentId = null, endAnnounced = false, lastDeviationAlertAt = null)
            }
            ActivityNavigationAction.SET_ALERTS_ENABLED -> {
                if (old == null) return@owned ActivityResult.Failure(ActivityFailure.NotFound)
                kind = ActivityEventKind.NAVIGATION_ALERTS_CHANGED
                old.copy(revision = nextRevision, alertsEnabled = checkNotNull(command.alertsEnabled))
            }
        }
        if (replacement != null && command.action == ActivityNavigationAction.FOLLOW_PLANNED) {
            val restored = restoreControls(record.id, checkNotNull(replacement))
            if (restored is ActivityResult.Failure) return@owned restored
        }
        val now = clock.now()
        val latest = current!!
        val updated = latest.copy(revision = latest.revision + 1, updatedAt = now, statistics = latest.statisticsAt(now), navigation = next, lastNavigationCommand = command)
        val event = ActivityEvent(activityIdentity(), eventSequence + 1, kind, now, navigationRouteId = next.routeId)
        val saved = repository.commit(ActivityCommit(latest.revision, updated, events = listOf(event), appliedCommandId = command.id, navigationSnapshot = frozen))
        if (saved is ActivityResult.Failure) { fail(saved.reason); return@owned saved }
        current = (saved as ActivityResult.Success<ActivityRecord>).value
        pendingStatistics = current!!.statistics
        eventSequence++
        if (replacement != null) navigator = replacement else navigator?.reanchor(next)
        publish(now)
        ActivityResult.Success(next)
    }

    override suspend fun apply(command: ActivityCommand): ActivityResult<ActivityRecord> = owned {
        if (!ActivityValidation.id(command.id) || !ActivityValidation.id(command.activityId) ||
            !ActivityValidation.id(command.sessionGeneration) || command.expectedControlRevision < 0)
            return@owned ActivityResult.Failure(ActivityFailure.InvalidInput("command"))
        when (val duplicate = repository.appliedCommand(command.activityId, command.id)) {
            is ActivityResult.Failure -> return@owned duplicate
            is ActivityResult.Success -> duplicate.value?.let {
                return@owned if (it.lastCommand == command) ActivityResult.Success(it) else ActivityResult.Failure(ActivityFailure.InvalidInput("commandIdentity"))
            }
        }
        val record = current ?: return@owned ActivityResult.Failure(ActivityFailure.NotFound)
        if (record.id != command.activityId || record.sessionGeneration != command.sessionGeneration)
            return@owned ActivityResult.Failure(ActivityFailure.StaleSession)
        if (record.controlRevision != command.expectedControlRevision) return@owned ActivityResult.Failure(ActivityFailure.RevisionConflict)
        if (command.action == ActivityAction.COMPLETE || command.action == ActivityAction.INTERRUPT)
            return@owned ActivityResult.Failure(ActivityFailure.InvalidInput("internalAction"))
        if (failure != null) return@owned ActivityResult.Failure(failure!!)
        if (ActivityLifecycle.next(record.status, command.action) == null)
            return@owned ActivityResult.Failure(ActivityFailure.InvalidTransition(record.status, command.action))
        if (command.action == ActivityAction.RESUME && record.status != ActivityStatus.RECORDING) {
            host.start(record.profile)?.let { return@owned ActivityResult.Failure(it) }
        }
        val now = clock.now()
        if (command.action == ActivityAction.PAUSE || command.action == ActivityAction.FINISH) pendingNavigationAlerts.clear()
        val flushed = flush(now)
        if (flushed is ActivityResult.Failure) return@owned flushed
        val changed = transition(command.action, now, command)
        if (changed is ActivityResult.Failure) return@owned changed
        when (command.action) {
            ActivityAction.PAUSE -> { stopLocations(); resetPath(); availability = ActivityDataAvailability.UNAVAILABLE }
            ActivityAction.RESUME -> if (record.status != ActivityStatus.RECORDING) { resetPath(); beginLocations(now); beginTicker() }
            ActivityAction.FINISH -> {
                stopLocations(); host.stop(); resetPath()
                val finalized = finalizeLocked()
                return@owned if (finalized is ActivityResult.Failure) finalized else changed
            }
            else -> Unit
        }
        publish(now)
        changed
    }

    private suspend fun transition(action: ActivityAction, time: ActivityTime, command: ActivityCommand? = null): ActivityResult<ActivityRecord> {
        val record = current ?: return ActivityResult.Failure(ActivityFailure.NotFound)
        val next = ActivityLifecycle.next(record.status, action) ?: return ActivityResult.Failure(ActivityFailure.InvalidTransition(record.status, action))
        val changed = next != record.status
        val kind = when (action) {
            ActivityAction.PAUSE -> ActivityEventKind.PAUSED
            ActivityAction.RESUME -> ActivityEventKind.RESUMED
            ActivityAction.FINISH -> ActivityEventKind.FINISH_REQUESTED
            ActivityAction.INTERRUPT -> ActivityEventKind.INTERRUPTED
            ActivityAction.COMPLETE -> ActivityEventKind.COMPLETED
            ActivityAction.MARK_CONTROL_POINT -> ActivityEventKind.CONTROL_POINT
        }
        val updated = record.copy(revision = record.revision + 1, controlRevision = record.controlRevision + if (changed) 1 else 0,
            status = next, updatedAt = time, statistics = record.statisticsAt(time), navigation = navigator?.state,
            sessionGeneration = if (record.status == ActivityStatus.INTERRUPTED && next == ActivityStatus.RECORDING) activityIdentity() else record.sessionGeneration,
            finishedAt = if (next == ActivityStatus.FINALIZING && record.finishedAt == null) time else record.finishedAt,
            lastCommand = command ?: record.lastCommand)
        val event = if (changed || action == ActivityAction.MARK_CONTROL_POINT) listOf(ActivityEvent(activityIdentity(), eventSequence + 1, kind, time,
            relatedId = if (action == ActivityAction.MARK_CONTROL_POINT) position?.id?.takeIf { availability == ActivityDataAvailability.LIVE } else null)) else emptyList()
        val result = repository.commit(ActivityCommit(record.revision, updated, events = event, appliedCommandId = command?.id))
        if (result is ActivityResult.Success) {
            current = result.value
            pendingStatistics = result.value.statistics
            eventSequence += event.size
            publish(time)
        } else if (result is ActivityResult.Failure) fail(result.reason)
        return result
    }

    private suspend fun finalizeLocked(): ActivityResult<ActivityRecord> {
        val record = current ?: return ActivityResult.Failure(ActivityFailure.NotFound)
        if (record.status != ActivityStatus.FINALIZING) return ActivityResult.Success(record)
        val derived = if (record.route == null) {
            when (val result = routes.derive(record.id, "recorded-${activityDigest(record.id)}")) {
                is ActivityResult.Failure -> { fail(result.reason); return result }
                is ActivityResult.Success -> result.value?.id
            }
        } else null
        val now = clock.now()
        val completed = record.copy(revision = record.revision + 1, controlRevision = record.controlRevision + 1,
            status = ActivityStatus.COMPLETED, updatedAt = now, derivedRouteId = derived)
        val result = repository.commit(ActivityCommit(record.revision, completed,
            events = listOf(ActivityEvent(activityIdentity(), eventSequence + 1, ActivityEventKind.COMPLETED, now))))
        if (result is ActivityResult.Success) {
            current = null
            packageUse.release()
            ticker?.cancel(); ticker = null
            mutableActive.value = null
            mutableLive.value = ActivityLiveState(result.value.owned(), result.value.statistics.distanceMeters, result.value.statistics.durations(),
                positionAvailability = ActivityDataAvailability.UNAVAILABLE, heartRateAvailability = ActivityDataAvailability.UNAVAILABLE)
        } else if (result is ActivityResult.Failure) fail(result.reason)
        return result
    }

    private fun beginLocations(since: ActivityTime) {
        navigator?.suspendPosition()
        recordingSince = since
        positionBarrier = null
        availability = ActivityDataAvailability.WAITING
        locationJob = scope.launch {
            locations.observe(LocationRequest(background = true)).collect { state -> mutex.withLock { receive(state) } }
        }
    }
    private fun stopLocations() { pendingNavigationAlerts.clear(); locationJob?.cancel(); locationJob = null; recordingSince = null }
    private fun resetPath() { navigator?.suspendPosition(); segmentId = null; lastPositionTime = null; position = null; speed = null; movement?.reset() }

    private fun beginTicker() {
        if (current == null || ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(ActivityLimits.MAX_COMMIT_INTERVAL_MILLIS)
                mutex.withLock {
                    val now = clock.now()
                    if (current?.status == ActivityStatus.RECORDING && failure == null) {
                        val last = lastPositionTime
                        if (availability == ActivityDataAvailability.LIVE && last != null && (activityTimeDelta(last, now) ?: Long.MAX_VALUE) > policy.maxPositionAgeMillis)
                            quality(ActivityDataAvailability.STALE, now)
                        flush(now)
                    }
                    publish(now)
                }
            }
        }
    }

    private suspend fun receive(state: LocationState) {
        val record = current ?: return
        if (record.status != ActivityStatus.RECORDING || failure != null) return
        val now = clock.now()
        if (state.deliveryGap) {
            events += ActivityEvent(activityIdentity(), eventSequence + events.size + 1, ActivityEventKind.TELEMETRY_GAP, now)
            interrupt(now)
            if (failure == null) failure = ActivityFailure.RecordingUnavailable
            publish(now)
            return
        }
        val fix = state.fix
        val sourceConflict = state.sourceConflict
        if (state.status == LocationStatus.PERMISSION_DENIED || state.status == LocationStatus.PRECISE_PERMISSION_REQUIRED) {
            interrupt(now)
            return
        }
        if (fix == null || state.status != LocationStatus.ACTIVE) {
            quality(if (state.status == LocationStatus.WAITING) ActivityDataAvailability.WAITING else ActivityDataAvailability.UNAVAILABLE, now)
            publish(now)
            return
        }
        val acquired = ActivityTime(fix.timestampMillis, fix.monotonicMillis ?: (now.monotonicMillis!! - (now.epochMillis - fix.timestampMillis)).coerceAtLeast(0), now.continuityId)
        val age = activityTimeDelta(acquired, now) ?: Long.MAX_VALUE
        val since = recordingSince ?: return
        if ((activityTimeDelta(since, acquired) ?: -1) < 0) return
        val freshConflict = sourceConflict && age in 0..policy.maxPositionAgeMillis
        if (!freshConflict && positionBarrier?.let { (activityTimeDelta(it, acquired) ?: -1) <= 0 } == true) return
        if (age !in 0..policy.maxPositionAgeMillis) { quality(ActivityDataAvailability.STALE, now); publish(now); return }
        if (sourceConflict) { quality(ActivityDataAvailability.SOURCE_CONFLICT, now); publish(now); return }
        if (!fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 || !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0.0..policy.maxPositionAccuracyMeters) {
            quality(ActivityDataAvailability.UNRELIABLE, now); publish(now); return
        }
        val delta = lastPositionTime?.let { activityTimeDelta(it, acquired) }
        if (lastPositionTime != null && (delta == null || delta <= 0)) return
        if (delta != null && delta <= 5 && position?.let {
                it.source.provider == fix.provider && it.acquiredAt.epochMillis == fix.timestampMillis
            } == true) return
        if (delta != null && delta > policy.pathGapMillis) {
            events += ActivityEvent(activityIdentity(), eventSequence + events.size + 1, ActivityEventKind.TELEMETRY_GAP, now)
            segmentId = null
            movement?.reset()
        }
        if (segmentId == null) segmentId = activityIdentity()
        quality(ActivityDataAvailability.LIVE, now)
        if (failure != null) return
        val source = MeasurementSource("phone-${fix.provider}", MeasurementSourceKind.PHONE_LOCATION, provider = fix.provider)
        val point = TelemetrySample(activityIdentity(), source, acquired, now, MeasurementPhase.RECORDING,
            PositionMeasurement(GeographicCoordinate(fix.latitude, fix.longitude), fix.accuracyMeters), segmentId)
        val batch = mutableListOf(point)
        fun add(measurement: ActivityMeasurement) {
            if (ActivityValidation.measurement(measurement) == null)
                batch += TelemetrySample(activityIdentity(), source, acquired, now, MeasurementPhase.RECORDING, measurement)
        }
        fix.speedMetersPerSecond?.let { add(SpeedMeasurement(it, fix.speedAccuracyMetersPerSecond)) }
        fix.courseDegrees?.let { add(CourseMeasurement(it)) }
        fix.altitudeMeters?.let { add(ElevationMeasurement(it, "PHONE_UNSPECIFIED", fix.altitudeAccuracyMeters)) }
        val bytes = batch.sumOf { json.encodeToString(it).encodeToByteArray().size }
        if (samples.size + batch.size > 128 || sampleBytes + bytes > 131_072 || events.size >= 110) {
            if (flush(now) is ActivityResult.Failure) return
        }
        if (batch.any { ActivityValidation.sample(it) != null }) { quality(ActivityDataAvailability.UNRELIABLE, now); publish(now); return }
        samples += batch
        sampleBytes += bytes
        val base = current!!.statisticsAt(now).copy(distanceMeters = pendingStatistics.distanceMeters, movingMillis = pendingStatistics.movingMillis)
        pendingStatistics = movement?.accept(point, fix.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }, base) ?: base
        lastPositionTime = acquired
        position = point
        speed = batch.lastOrNull { it.measurement is SpeedMeasurement }
        navigator?.let { guidance ->
            val updates = withContext(Dispatchers.Default) { guidance.update(point, now, fix.speedMetersPerSecond, fix.courseDegrees) }
            for (update in updates.events) {
                val event = ActivityEvent(activityIdentity(), eventSequence + events.size + 1, update.kind, now, update.relatedId, guidance.state.routeId)
                events += event
                if (guidance.state.alertsEnabled) pendingNavigationAlerts += ActivityNavigationAlert(event.id, record.id, guidance.state.routeId, update.kind, now, update.relatedId)
            }
        }
        publish(now)
    }

    private suspend fun quality(value: ActivityDataAvailability, time: ActivityTime) {
        if (availability == value || failure != null) return
        if (events.size >= 120 && flush(time) is ActivityResult.Failure) return
        if (value != ActivityDataAvailability.LIVE) { positionBarrier = time; navigator?.suspendPosition(); pendingNavigationAlerts.clear() }
        val wasLive = availability == ActivityDataAvailability.LIVE
        availability = value
        if (value == ActivityDataAvailability.SOURCE_CONFLICT)
            events += ActivityEvent(activityIdentity(), eventSequence + events.size + 1, ActivityEventKind.SOURCE_CONFLICT, time)
        if (wasLive && value != ActivityDataAvailability.LIVE) {
            events += ActivityEvent(activityIdentity(), eventSequence + events.size + 1, ActivityEventKind.POSITION_LOST, time)
            segmentId = null
            position = null; speed = null
            movement?.reset()
        } else if (!wasLive && value == ActivityDataAvailability.LIVE) {
            events += ActivityEvent(activityIdentity(), eventSequence + events.size + 1, ActivityEventKind.POSITION_RECOVERED, time)
        }
    }

    private suspend fun flush(now: ActivityTime): ActivityResult<ActivityRecord> {
        val record = current ?: return ActivityResult.Failure(ActivityFailure.NotFound)
        if (failure != null) return ActivityResult.Failure(failure!!)
        if (record.status != ActivityStatus.RECORDING && samples.isEmpty() && events.isEmpty()) return ActivityResult.Success(record)
        val statistics = record.statisticsAt(now).copy(distanceMeters = pendingStatistics.distanceMeters, movingMillis = pendingStatistics.movingMillis)
        val result = repository.commit(ActivityCommit(record.revision,
            record.copy(revision = record.revision + 1, updatedAt = now, statistics = statistics, navigation = navigator?.state), samples.toList(), events.toList()))
        if (result is ActivityResult.Success) {
            current = result.value
            pendingStatistics = result.value.statistics
            eventSequence += events.size
            pendingNavigationAlerts.forEach { mutableNavigationAlerts.tryEmit(it) }
            pendingNavigationAlerts.clear()
            samples.clear(); events.clear(); sampleBytes = 0
            publish(now)
        } else if (result is ActivityResult.Failure) fail(result.reason)
        return result
    }

    private suspend fun interrupt(time: ActivityTime) {
        val record = current ?: return
        if (record.status != ActivityStatus.RECORDING && record.status != ActivityStatus.PAUSED) return
        stopLocations(); host.stop()
        if (failure == null && flush(time) is ActivityResult.Success) transition(ActivityAction.INTERRUPT, time)
        resetPath()
        availability = ActivityDataAvailability.UNAVAILABLE
        publish(time)
    }

    private fun fail(reason: ActivityFailure) {
        failure = reason
        stopLocations(); host.stop()
        resetPath()
        availability = ActivityDataAvailability.UNAVAILABLE
        samples.clear(); events.clear(); pendingNavigationAlerts.clear(); sampleBytes = 0
        publish(clock.now())
    }

    private fun publish(now: ActivityTime) {
        val record = current
        mutableActive.value = record?.owned()
        if (record == null) { mutableLive.value = null; return }
        val statistics = if (failure == null) record.statisticsAt(now).copy(distanceMeters = pendingStatistics.distanceMeters, movingMillis = pendingStatistics.movingMillis)
            else record.statistics
        mutableLive.value = ActivityLiveState(record.owned(), statistics.distanceMeters, statistics.durations(), position, speed,
            progress = navigator?.progress, positionAvailability = availability, heartRateAvailability = ActivityDataAvailability.UNAVAILABLE, failure = failure)
    }
}
