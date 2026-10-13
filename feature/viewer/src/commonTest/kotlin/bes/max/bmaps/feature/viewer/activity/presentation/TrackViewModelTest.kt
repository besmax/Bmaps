/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.sharing.PickedDocument
import kotlinx.io.Buffer
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.mapengine.TilePyramid
import bes.max.bmaps.core.mapengine.ZoomRange
import bes.max.bmaps.domain.activity.*
import bmaps.feature.viewer.generated.resources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TrackViewModelTest {
    @Test fun routePreparationKeepsReverseIntermediateStartAndDoesNotStartUntilRequested() = runTest {
        fixture { model, session ->
            model.mapCoverage(TilePyramid(ZoomRange(2, 2), 0, 0, 1, 1))
            model.selectRoute("route")
            runCurrent()
            model.await { it.preparing && !it.busy && it.coverageMissing }
            assertNull(session.live.value)
            model.reverse(true)
            model.segment(1)
            model.edge(1)
            model.profile(1)
            model.start()
            runCurrent()
            model.await { it.metrics != null && !it.busy }
            val request = session.starts.single()
            assertEquals(ActivityProfile.OFF_ROAD_MOTORCYCLE, request.profile)
            val selection = assertNotNull(request.route)
            assertEquals(RouteDirection.REVERSE, selection.direction)
            assertEquals(RoutePosition(1, 1, 1.0), selection.start)
            assertEquals(6, selection.snapshot.segments.sumOf { it.vertices.size })
            assertTrue(model.state.value.following)
            assertFalse(model.state.value.expanded)
            model.inspect()
            assertFalse(model.state.value.following)
            assertEquals(1, session.starts.size)
        }
    }

    @Test fun deniedStartRemainsReviewableAndRouteFreeRetryUsesNoSnapshot() = runTest {
        fixture { model, session ->
            session.startFailure = ActivityFailure.PreciseLocationRequired
            model.prepareFree()
            model.start()
            runCurrent()
            model.await { !it.busy && it.error != null }
            assertTrue(model.state.value.preparing)
            assertEquals(Res.string.track_permission_required, model.state.value.error)
            assertNull(session.live.value)
            session.startFailure = null
            model.start()
            runCurrent()
            model.await { it.metrics != null && !it.busy }
            assertNull(session.starts.last().route)
            assertFalse(model.state.value.metrics!!.hasNavigation)
        }
    }

    @Test fun pauseAndFinishUseCurrentIdentityAndCollapsingNeverSendsACommand() = runTest {
        fixture { model, session ->
            model.prepareFree(); model.start(); runCurrent()
            model.await { it.metrics != null && !it.busy }
            model.collapse(); model.inspect(); model.followPosition()
            assertTrue(session.commands.isEmpty())
            model.pauseResume(); runCurrent()
            model.await { it.metrics?.resumable == true && !it.busy }
            val command = session.commands.single()
            val record = session.live.value!!.record
            assertEquals(record.id, command.activityId)
            assertEquals(record.sessionGeneration, command.sessionGeneration)
            assertEquals(ActivityAction.PAUSE, command.action)
            model.confirmFinish(true)
            assertEquals(1, session.commands.size)
            model.confirmFinish(false)
            assertEquals(ActivityStatus.PAUSED, record.status)
        }
    }

    @Test fun directMultiRouteImportOpensChooserAndClearsPreviousPreparation() = runTest {
        fixture { model, session ->
            model.selectRoute("route"); runCurrent()
            model.await { it.preparing && !it.busy }
            model.collapse()
            val tracks = (1..2).joinToString("") { index ->
                """<trk><name>Track $index</name><trkseg><trkpt lat="50" lon="30"/><trkpt lat="50.01" lon="30.01"/></trkseg></trk>"""
            }
            model.importGpx(PickedDocument("routes.gpx", {
                Buffer().apply { write("<gpx version=\"1.1\">$tracks</gpx>".encodeToByteArray()) }
            }))
            runCurrent()
            model.await { !it.busy && it.routes.size == 2 }
            assertTrue(model.state.value.expanded)
            assertTrue(model.state.value.choosing)
            assertFalse(model.state.value.preparing)
            assertNull(model.state.value.route)
            assertTrue(model.state.value.preview.isEmpty())
            assertTrue(model.state.value.selectedSection.isEmpty())
            assertTrue(session.starts.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (TrackViewModel, TrackTestSession) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val session = TrackTestSession()
        try {
            val model = TrackViewModel(session, TrackTestRepository(session), TrackTestRoutes())
            store.put("track", model)
            runCurrent()
            block(model, session)
        } finally {
            store.clear(); runCurrent(); Dispatchers.resetMain()
        }
        assertFalse(session.commands.any { it.action == ActivityAction.FINISH })
    }
    private suspend fun TrackViewModel.await(predicate: (TrackState) -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5000) { state.first(predicate) }
    }
}

private class TrackTestSession : ActivitySession {
    override val live = MutableStateFlow<ActivityLiveState?>(null)
    override val active = live.map { it?.record }
    override val navigationAlerts = MutableSharedFlow<ActivityNavigationAlert>()
    val starts = mutableListOf<ActivityStartRequest>()
    val commands = mutableListOf<ActivityCommand>()
    var startFailure: ActivityFailure? = null
    override fun initialize() = Unit
    override suspend fun start(request: ActivityStartRequest): ActivityResult<ActivityRecord> {
        starts += request
        startFailure?.let { return ActivityResult.Failure(it) }
        val time = ActivityTime(1000, 1000, "clock")
        val navigation = request.route?.let { ActivityNavigationState(ActivityNavigationMode.PLANNED_ROUTE, it.snapshot.id,
            it.snapshot.revision, it.direction, it.start, it.start) }
        val record = ActivityRecord(request.id, 0, "generation", request.name, request.profile, ActivityStatus.RECORDING,
            time, time, request.route, navigation = navigation)
        live.value = ActivityLiveState(record, 0.0, ActivityDurations(0, 0, 0, 0))
        return ActivityResult.Success(record)
    }
    override suspend fun apply(command: ActivityCommand): ActivityResult<ActivityRecord> {
        commands += command
        val record = live.value!!.record
        val next = record.copy(status = ActivityLifecycle.next(record.status, command.action)!!,
            controlRevision = record.controlRevision + 1)
        live.value = live.value!!.copy(record = next)
        return ActivityResult.Success(next)
    }
    override suspend fun recover() = ActivityResult.Success(live.value?.record)
    override suspend fun navigate(command: ActivityNavigationCommand): ActivityResult<ActivityNavigationState> = ActivityResult.Failure(ActivityFailure.NotFound)
}

private class TrackTestRepository(private val session: TrackTestSession) : ActivityRepository {
    override suspend fun get(id: String): ActivityResult<ActivityRecord> = session.live.value?.record?.let { ActivityResult.Success(it) } ?: ActivityResult.Failure(ActivityFailure.NotFound)
    override suspend fun navigationRoute(activityId: String) = ActivityResult.Success(session.live.value?.record?.route?.snapshot)
    override suspend fun positions(activityId: String, query: ActivityPositionQuery) = ActivityResult.Success(ActivityPositionPage(emptyList(), null))
    override suspend fun create(record: ActivityRecord, started: ActivityEvent): ActivityResult<ActivityRecord> = error("unused")
    override suspend fun commit(value: ActivityCommit): ActivityResult<ActivityRecord> = error("unused")
    override suspend fun history(query: ActivityHistoryQuery): ActivityResult<ActivityHistoryPage> = error("unused")
    override suspend fun telemetry(activityId: String, query: TelemetryQuery): ActivityResult<TelemetryPage> = error("unused")
    override suspend fun events(activityId: String, query: ActivityEventQuery): ActivityResult<ActivityEventPage> = error("unused")
    override suspend fun delete(id: String): ActivityResult<Unit> = error("unused")
    override suspend fun reconcile() = ActivityResult.Success(Unit)
    override suspend fun appliedCommand(activityId: String, commandId: String): ActivityResult<ActivityRecord?> = error("unused")
    override fun observeActive() = session.active
}

private class TrackTestRoutes : ActivityRouteRepository {
    override suspend fun get(id: String) = ActivityResult.Success(ActivityRoute(id, 0, "Trail",
        listOf(RouteSegment("first", listOf(RouteVertex(GeographicCoordinate(55.0, 37.0)), RouteVertex(GeographicCoordinate(55.001, 37.0)), RouteVertex(GeographicCoordinate(55.002, 37.0)))),
            RouteSegment("second", listOf(RouteVertex(GeographicCoordinate(55.01, 37.0)), RouteVertex(GeographicCoordinate(55.011, 37.0)), RouteVertex(GeographicCoordinate(55.012, 37.0)))))))
    override suspend fun routes(query: ActivityRouteQuery) = ActivityResult.Success(ActivityRoutePage(emptyList(), null))
    override suspend fun saveImported(routes: List<ActivityRoute>) = ActivityResult.Success(Unit)
    override suspend fun save(route: ActivityRoute, expectedRevision: Long?): ActivityResult<ActivityRoute> = error("unused")
    override suspend fun derive(activityId: String, routeId: String): ActivityResult<ActivityRoute?> = error("unused")
    override suspend fun delete(id: String): ActivityResult<Unit> = error("unused")
}
