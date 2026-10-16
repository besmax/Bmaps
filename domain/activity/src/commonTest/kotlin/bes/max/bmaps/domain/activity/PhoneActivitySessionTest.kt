/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import bes.max.bmaps.core.location.*
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneActivitySessionTest {
    @Test
    fun manualControlPointIsDurableDeduplicatedAndDoesNotChangeLifecycle() = runTest {
        fixture { f ->
            f.start()
            f.fix(55.0)
            advanceTimeBy(1000); runCurrent()
            val command = f.command(ActivityAction.MARK_CONTROL_POINT)
            val recorded = f.session.apply(command).success()
            val marked = f.repository.eventLog.single { it.kind == ActivityEventKind.CONTROL_POINT }
            assertNotNull(marked.relatedId)
            assertEquals(ActivityStatus.RECORDING, recorded.status)
            assertEquals(command.expectedControlRevision, recorded.controlRevision)
            f.session.apply(command).success()
            assertEquals(1, f.repository.eventLog.count { it.kind == ActivityEventKind.CONTROL_POINT })
            f.session.apply(f.command(ActivityAction.PAUSE)).success()
            assertIs<ActivityResult.Failure>(f.session.apply(f.command(ActivityAction.MARK_CONTROL_POINT)))
            assertEquals(1, f.repository.eventLog.count { it.kind == ActivityEventKind.CONTROL_POINT })
        }
    }

    @Test
    fun pauseStopsEveryPhoneChannelAndResumeBreaksPath() = runTest {
        fixture { f ->
            f.start()
            f.fix(55.0)
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.0001)
            advanceTimeBy(1_000); runCurrent()
            val pause = f.command(ActivityAction.PAUSE)
            f.session.apply(pause).success()
            runCurrent()
            assertEquals(0, f.native.subscribers)
            val count = f.repository.samples.size
            f.fix(55.1)
            advanceTimeBy(5_000); runCurrent()
            assertEquals(count, f.repository.samples.size)
            assertEquals(ActivityDataAvailability.UNAVAILABLE, f.session.live.value?.heartRateAvailability)
            val resumed = f.session.apply(f.command(ActivityAction.RESUME)).success()
            runCurrent()
            assertEquals(1, f.native.subscribers)
            f.fix(55.1)
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.1001)
            advanceTimeBy(1_000); runCurrent()
            val points = f.repository.samples.filter { it.measurement is PositionMeasurement }
            assertEquals(4, points.size)
            assertNotEquals(points[1].pathSegmentId, points[2].pathSegmentId)
            assertTrue(f.session.live.value!!.distanceMeters < 100)
            assertEquals(0, f.repository.samples.count { it.phase != MeasurementPhase.RECORDING })
            assertEquals(resumed.controlRevision, f.session.active.value?.controlRevision)
            assertEquals(ActivityStatus.PAUSED, f.session.apply(pause).success().status)
            assertEquals(ActivityStatus.RECORDING, f.session.active.value?.status)
            assertIs<ActivityResult.Failure>(f.session.apply(pause.copy(action = ActivityAction.FINISH)))
        }
    }

    @Test
    fun cachedDuplicatePoorAndConflictingFixesDoNotAccumulate() = runTest {
        fixture { f ->
            f.start()
            f.fix(55.0)
            f.fix(55.0001)
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.1, accuracy = 500.0)
            f.fix(55.2, conflict = true)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, f.repository.samples.count { it.measurement is PositionMeasurement })
            assertEquals(ActivityDataAvailability.SOURCE_CONFLICT, f.session.live.value?.positionAvailability)
            assertTrue(f.repository.eventLog.any { it.kind == ActivityEventKind.POSITION_LOST })
            assertTrue(f.repository.eventLog.any { it.kind == ActivityEventKind.SOURCE_CONFLICT })
            f.fix(55.0001)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, f.repository.samples.count { it.measurement is PositionMeasurement })
            assertEquals(0.0, f.session.live.value!!.distanceMeters)
        }
    }

    @Test
    fun sharedMapSubscriptionSurvivesRecordingPauseAndReleasesAfterMapExit() = runTest {
        fixture { f ->
            val map = launch { f.locations.observe().collect() }
            runCurrent()
            assertEquals(1, f.native.subscribers)
            f.start()
            assertEquals(1, f.native.subscribers)
            assertTrue(f.native.request!!.background)
            f.session.apply(f.command(ActivityAction.PAUSE)).success()
            runCurrent()
            assertEquals(1, f.native.subscribers)
            assertFalse(f.native.request!!.background)
            map.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.native.subscribers)
        }
    }

    @Test
    fun restartUsesDurableCheckpointAndRequiresExplicitNewGeneration() = runTest {
        fixture { f ->
            val oldTime = f.clock.now().copy(epochMillis = 1_000, monotonicMillis = 1_000, continuityId = "old-process")
            f.repository.value.value = ActivityRecord("recovered", 4, "old-generation", "Trip", ActivityProfile.HIKING,
                ActivityStatus.RECORDING, oldTime.copy(epochMillis = 500, monotonicMillis = 500), oldTime,
                statistics = ActivityStatistics(elapsedMillis = 500, recordingMillis = 500))
            f.repository.last = f.repository.value.value
            f.repository.eventLog += ActivityEvent("started", 1, ActivityEventKind.STARTED, f.repository.value.value!!.startedAt)
            val interrupted = assertNotNull(f.session.recover().success())
            assertEquals(ActivityStatus.INTERRUPTED, interrupted.status)
            assertEquals(0, f.host.starts)
            assertEquals(0, f.native.subscribers)
            assertEquals(500L, interrupted.statistics.recordingMillis)
            advanceTimeBy(10_000); runCurrent()
            val resumed = f.session.apply(f.command(ActivityAction.RESUME)).success()
            runCurrent()
            assertNotEquals("old-generation", resumed.sessionGeneration)
            assertEquals(500L, resumed.statistics.recordingMillis)
            assertTrue(resumed.statistics.elapsedIsEstimated)
            assertEquals(ActivityFailure.StaleSession, assertIs<ActivityResult.Failure>(f.session.apply(
                f.command(ActivityAction.PAUSE).copy(sessionGeneration = "old-generation"))).reason)
        }
    }

    @Test
    fun storageFailureStopsSensorsAndFinalizationRetriesWithoutRecording() = runTest {
        fixture { f ->
            f.start()
            f.fix(55.0)
            f.repository.failWrites = true
            advanceTimeBy(1_000); runCurrent()
            assertEquals(0, f.native.subscribers)
            assertEquals(ActivityFailure.StorageUnavailable, f.session.live.value?.failure)
            assertTrue(f.host.stops > 0)
            f.repository.failWrites = false
            assertEquals(ActivityStatus.INTERRUPTED, f.session.recover().success()?.status)
            f.repository.failCompletion = true
            val finish = f.command(ActivityAction.FINISH)
            assertIs<ActivityResult.Failure>(f.session.apply(finish))
            assertEquals(ActivityStatus.FINALIZING, f.repository.value.value?.status)
            assertEquals(0, f.native.subscribers)
            val starts = f.host.starts
            f.repository.failCompletion = false
            assertNull(f.session.recover().success())
            assertEquals(starts, f.host.starts)
            assertEquals(1, f.routes.identities.size)
            assertEquals(ActivityStatus.FINALIZING, f.session.apply(finish).success().status)
        }
    }

    @Test
    fun batchedNativePositionsAreNotConflatedIntoTheLastFix() = runTest {
        fixture { f ->
            f.start()
            advanceTimeBy(1_000); runCurrent()
            val now = f.clock.now()
            for (index in 1..20) {
                val time = now.epochMillis - 1_000 + index
                assertTrue(f.native.states.tryEmit(LocationState(LocationStatus.ACTIVE, LocationFix(55.0, 37.0, 3.0,
                    time, monotonicMillis = time, provider = "gps"))))
            }
            runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(20, f.repository.samples.count { it.measurement is PositionMeasurement })
        }
    }

    @Test
    fun nativeDeliveryOverflowInterruptsInsteadOfSilentlyLosingTelemetry() = runTest {
        fixture { f ->
            f.start()
            f.native.states.emit(LocationState(LocationStatus.UNAVAILABLE, deliveryGap = true))
            runCurrent()
            assertEquals(ActivityStatus.INTERRUPTED, f.session.active.value?.status)
            assertEquals(ActivityFailure.RecordingUnavailable, f.session.live.value?.failure)
            assertTrue(f.repository.eventLog.any { it.kind == ActivityEventKind.TELEMETRY_GAP })
            assertEquals(0, f.native.subscribers)
        }
    }

    @Test
    fun navigationCorrectionChangesGuidanceWithoutChangingTelemetryOrPlan() = runTest {
        fixture { f ->
            val route = ActivityRoute("plan", 0, "Plan", listOf(RouteSegment("leg", listOf(
                RouteVertex(GeographicCoordinate(55.0, 37.0)), RouteVertex(GeographicCoordinate(55.003, 37.0))))))
            f.start(RouteSelection(route, RouteDirection.FORWARD, RoutePosition(0, 0, 0.0)))
            f.fix(55.0, course = 0.0)
            f.session.apply(f.command(ActivityAction.RESUME)).success()
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.0001, course = 0.0)
            f.session.apply(f.command(ActivityAction.RESUME)).success()
            assertEquals(RouteMatchQuality.MATCHED, f.session.live.value?.progress?.quality)
            val samples = f.repository.samples.toList()
            val record = f.session.active.value!!
            val correction = ActivityNavigationCommand("correct", record.id, record.sessionGeneration, 0,
                ActivityNavigationAction.CORRECT_POSITION, RoutePosition(0, 0, 0.5))
            val state = f.session.navigate(correction).success()
            assertEquals(1L, state.revision)
            assertEquals(samples, f.repository.samples)
            assertEquals(route, f.session.active.value?.route?.snapshot)
            assertEquals(record.controlRevision, f.session.active.value?.controlRevision)
            assertEquals(state, f.session.navigate(correction).success())
            assertEquals(1, f.repository.eventLog.count { it.kind == ActivityEventKind.NAVIGATION_CORRECTED })
            assertIs<ActivityResult.Failure>(f.session.navigate(correction.copy(id = "stale-correction")))
            assertIs<ActivityResult.Failure>(f.session.navigate(correction.copy(position = RoutePosition(0, 0, 0.8))))
        }
    }

    @Test
    fun recordedReturnIsAFrozenReverseSnapshotWithGapsAndRecordingContinues() = runTest {
        fixture { f ->
            f.start()
            f.fix(55.0)
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.0001)
            f.session.apply(f.command(ActivityAction.PAUSE)).success()
            advanceTimeBy(5_000); runCurrent()
            f.session.apply(f.command(ActivityAction.RESUME)).success(); runCurrent()
            f.fix(55.01)
            val record = f.session.active.value!!
            val returning = ActivityNavigationCommand("return", record.id, record.sessionGeneration, 0, ActivityNavigationAction.RETURN_ALONG_RECORDING)
            assertIs<ActivityResult.Failure>(f.session.navigate(returning))
            assertEquals(ActivityStatus.RECORDING, f.session.active.value?.status)
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.0101)
            f.session.apply(f.command(ActivityAction.RESUME)).success()
            val before = f.repository.samples.toList()
            val returned = f.session.navigate(returning).success()
            assertEquals(ActivityNavigationMode.RECORDED_RETURN, returned.mode)
            assertEquals(RouteDirection.REVERSE, returned.direction)
            val frozen = f.repository.navigationRoute(record.id).success()!!
            assertEquals(listOf(2, 2), frozen.segments.map { it.vertices.size })
            assertEquals(before, f.repository.samples)
            assertNull(f.session.active.value?.route)
            advanceTimeBy(1_000); runCurrent()
            f.fix(55.0102, course = 0.0)
            f.session.apply(f.command(ActivityAction.RESUME)).success()
            assertEquals(frozen, f.repository.navigationRoute(record.id).success())
            assertTrue(f.repository.samples.size > before.size)
            assertEquals(returned, f.session.navigate(returning).success())
        }
    }

    @Test
    fun startPermissionFailureDoesNotCreateAnActivity() = runTest {
        fixture { f ->
            f.host.failure = ActivityFailure.PreciseLocationRequired
            assertEquals(ActivityFailure.PreciseLocationRequired, assertIs<ActivityResult.Failure>(f.session.start(
                ActivityStartRequest("activity", "Trip", ActivityProfile.HIKING))).reason)
            assertNull(f.repository.value.value)
            assertEquals(0, f.native.subscribers)
        }
    }

    private suspend fun TestScope.fixture(block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture(testScheduler)
        try { block(f) } finally {
            f.repository.failWrites = false; f.repository.failCompletion = false
            f.host.failure = null
            if (f.session.active.value != null) {
                f.session.recover()
                if (f.session.active.value != null) f.session.apply(f.command(ActivityAction.FINISH))
            }
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(private val scheduler: TestCoroutineScheduler) {
        val native = FakeLocation()
        val locations = SharedLocationService(native)
        val repository = MemoryActivities()
        val routes = MemoryRoutes()
        val host = FakeHost()
        val clock = object : ActivityClock {
            override fun now() = ActivityTime(100_000 + scheduler.currentTime, 100_000 + scheduler.currentTime, "phone-process")
        }
        val session = PhoneActivitySession(repository, routes, locations, host, clock, bes.max.bmaps.core.storage.PackageUseGuard())
        suspend fun start(route: RouteSelection? = null) { session.start(ActivityStartRequest("activity", "Trip", ActivityProfile.HIKING, route)).success(); scheduler.runCurrent() }
        suspend fun fix(latitude: Double, accuracy: Double = 3.0, conflict: Boolean = false, course: Double? = 90.0) {
            val time = clock.now()
            native.states.emit(LocationState(LocationStatus.ACTIVE, LocationFix(latitude, 37.0, accuracy, time.epochMillis,
                speedMetersPerSecond = 2.0, courseDegrees = course, monotonicMillis = time.monotonicMillis,
                provider = "gps", altitudeMeters = -5.0, altitudeAccuracyMeters = 2.0), conflict))
            scheduler.runCurrent()
        }
        fun command(action: ActivityAction): ActivityCommand {
            val record = session.active.value!!
            return ActivityCommand(activityIdentity(), record.id, record.sessionGeneration, record.controlRevision, action)
        }
    }

    private class FakeLocation : LocationService {
        val states = MutableSharedFlow<LocationState>(extraBufferCapacity = 64)
        var subscribers = 0
        var request: LocationRequest? = null
        override fun observe(request: LocationRequest): Flow<LocationState> = flow {
            subscribers++; this@FakeLocation.request = request
            try { emitAll(states) } finally { subscribers-- }
        }
    }
    private class FakeHost : ActivityRecordingHost {
        override val interruptions: Flow<Unit> = emptyFlow()
        var starts = 0
        var stops = 0
        var failure: ActivityFailure? = null
        override suspend fun start(profile: ActivityProfile): ActivityFailure? { starts++; return failure }
        override fun stop() { stops++ }
    }
    private class MemoryRoutes : ActivityRouteRepository {
        val identities = mutableSetOf<String>()
        override suspend fun derive(activityId: String, routeId: String): ActivityResult<ActivityRoute?> { identities += activityId; return ActivityResult.Success(null) }
        override suspend fun get(id: String): ActivityResult<ActivityRoute> = ActivityResult.Failure(ActivityFailure.NotFound)
        override suspend fun save(route: ActivityRoute, expectedRevision: Long?) = ActivityResult.Success(route)
        override suspend fun delete(id: String) = ActivityResult.Success(Unit)
        override suspend fun routes(query: ActivityRouteQuery) = ActivityResult.Success(ActivityRoutePage(emptyList(), null))
        override suspend fun saveImported(routes: List<ActivityRoute>) = ActivityResult.Success(Unit)
    }
    private class MemoryActivities : ActivityRepository {
        val value = MutableStateFlow<ActivityRecord?>(null)
        val samples = mutableListOf<TelemetrySample>()
        val eventLog = mutableListOf<ActivityEvent>()
        val navigationSnapshots = mutableMapOf<String, ActivityRoute>()
        val commands = mutableMapOf<String, ActivityRecord>()
        var last: ActivityRecord? = null
        var failWrites = false
        var failCompletion = false
        override suspend fun create(record: ActivityRecord, started: ActivityEvent): ActivityResult<ActivityRecord> {
            if (value.value != null) return ActivityResult.Failure(ActivityFailure.AnotherActivityActive)
            value.value = record; last = record; eventLog += started
            return ActivityResult.Success(record)
        }
        override suspend fun get(id: String): ActivityResult<ActivityRecord> = last?.let { ActivityResult.Success(it) } ?: ActivityResult.Failure(ActivityFailure.NotFound)
        override suspend fun commit(value: ActivityCommit): ActivityResult<ActivityRecord> {
            if (failWrites || failCompletion && value.record.status == ActivityStatus.COMPLETED) return ActivityResult.Failure(ActivityFailure.StorageUnavailable)
            if (last?.revision != value.expectedRevision) return ActivityResult.Failure(ActivityFailure.RevisionConflict)
            assertNull(ActivityValidation.record(value.record))
            assertTrue(value.samples.all { ActivityValidation.sample(it) == null })
            value.navigationSnapshot?.let { navigationSnapshots[it.id] = it }
            last = value.record
            this.value.value = value.record.takeIf { it.status != ActivityStatus.COMPLETED }
            samples += value.samples; eventLog += value.events
            value.appliedCommandId?.let { commands[it] = value.record }
            return ActivityResult.Success(value.record)
        }
        override suspend fun appliedCommand(activityId: String, commandId: String) = ActivityResult.Success(commands[commandId])
        override fun observeActive(): Flow<ActivityRecord?> = value
        override suspend fun reconcile() = ActivityResult.Success(Unit)
        override suspend fun navigationRoute(activityId: String): ActivityResult<ActivityRoute?> {
            val record = last ?: value.value
            val nav = record?.navigation
            return ActivityResult.Success(if (nav?.mode == ActivityNavigationMode.RECORDED_RETURN) navigationSnapshots[nav.routeId] else record?.route?.snapshot)
        }
        override suspend fun positions(activityId: String, query: ActivityPositionQuery): ActivityResult<ActivityPositionPage> {
            val rows = samples.withIndex().filter { it.index + 1 > query.afterSequence && it.value.measurement is PositionMeasurement }.take(query.limit + 1)
            val page = rows.take(query.limit)
            return ActivityResult.Success(ActivityPositionPage(page.map { it.value }, if (rows.size > query.limit) page.last().index + 1L else null))
        }
        override suspend fun events(activityId: String, query: ActivityEventQuery) = ActivityResult.Success(ActivityEventPage(eventLog.filter { it.sequence > (query.afterSequence ?: 0) }, null))
        override suspend fun history(query: ActivityHistoryQuery) = ActivityResult.Success(ActivityHistoryPage(emptyList(), null))
        override suspend fun telemetry(activityId: String, query: TelemetryQuery) = ActivityResult.Success(TelemetryPage(samples.toList(), null))
        override suspend fun delete(id: String) = ActivityResult.Success(Unit)
    }
}

private fun <T> ActivityResult<T>.success(): T = assertIs<ActivityResult.Success<T>>(this).value
