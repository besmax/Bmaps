/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.domain.activity.*
import bmaps.feature.viewer.generated.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @Test fun fastInitialLoadAndRefreshNeverShowLoadingIndicator() = runTest {
        fixture { model, repository ->
            repository.historyDelayMillis = 100
            repeat(2) {
                model.refresh(); runCurrent()
                assertTrue(model.state.value.busy)
                assertFalse(model.state.value.loadingVisible)
                advanceTimeBy(100); runCurrent()
                assertFalse(model.state.value.busy)
                assertFalse(model.state.value.loadingVisible)
                assertEquals(listOf("first"), model.state.value.activities.map { it.id })
                advanceTimeBy(500); runCurrent()
                assertFalse(model.state.value.loadingVisible)
            }
        }
    }
    @Test fun slowLoadShowsIndicatorAfterDelayAndKeepsItVisibleWithoutDelayingResults() = runTest {
        fixture { model, repository ->
            repository.historyDelayMillis = 300
            model.refresh(); runCurrent()
            advanceTimeBy(249); runCurrent()
            assertFalse(model.state.value.loadingVisible)
            advanceTimeBy(1); runCurrent()
            assertTrue(model.state.value.loadingVisible)
            advanceTimeBy(50); runCurrent()
            assertFalse(model.state.value.busy)
            assertEquals(listOf("first"), model.state.value.activities.map { it.id })
            assertTrue(model.state.value.loadingVisible)
            advanceTimeBy(299); runCurrent()
            assertTrue(model.state.value.loadingVisible)
            advanceTimeBy(1); runCurrent()
            assertFalse(model.state.value.loadingVisible)
        }
    }
    @Test fun longLoadHidesIndicatorAsSoonAsItFinishesAfterMinimumVisibility() = runTest {
        fixture { model, repository ->
            repository.historyDelayMillis = 1000
            model.refresh(); runCurrent()
            advanceTimeBy(999); runCurrent()
            assertTrue(model.state.value.loadingVisible)
            assertTrue(model.state.value.busy)
            advanceTimeBy(1); runCurrent()
            assertFalse(model.state.value.loadingVisible)
            assertFalse(model.state.value.busy)
        }
    }
    @Test fun replacementSearchKeepsVisibleIndicatorAndCancelsStaleLoad() = runTest {
        fixture { model, repository ->
            repository.historyDelayMillis = 1000
            model.refresh(); runCurrent()
            advanceTimeBy(250); runCurrent()
            assertTrue(model.state.value.loadingVisible)
            repository.historyDelayMillis = 100
            model.search("Ride"); runCurrent()
            assertTrue(model.state.value.loadingVisible)
            advanceTimeBy(100); runCurrent()
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.loadingVisible)
            assertEquals("Ride", repository.queries.last().nameContains)
            advanceTimeBy(250); runCurrent()
            assertFalse(model.state.value.loadingVisible)
            advanceTimeBy(1000); runCurrent()
            assertFalse(model.state.value.loadingVisible)
        }
    }
    @Test fun fastReplacementSearchCancelsPendingIndicator() = runTest {
        fixture { model, repository ->
            repository.historyDelayMillis = 1000
            model.refresh(); runCurrent()
            advanceTimeBy(100); runCurrent()
            repository.historyDelayMillis = 100
            model.search("Ride"); runCurrent()
            advanceTimeBy(100); runCurrent()
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.loadingVisible)
            advanceTimeBy(1000); runCurrent()
            assertFalse(model.state.value.loadingVisible)
            assertEquals("Ride", repository.queries.last().nameContains)
        }
    }
    @Test fun failedLoadKeepsItsErrorWhileIndicatorFinishesItsMinimumVisibility() = runTest {
        fixture { model, repository ->
            repository.historyDelayMillis = 300
            repository.failHistory = true
            model.refresh(); runCurrent()
            advanceTimeBy(300); runCurrent()
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.loadingVisible)
            assertEquals(Res.string.history_error, model.state.value.error)
            advanceTimeBy(300); runCurrent()
            assertFalse(model.state.value.loadingVisible)
            assertEquals(Res.string.history_error, model.state.value.error)
        }
    }
    @Test fun historyPagingAndSearchAreSentToTheRepository() = runTest {
        fixture { model, repository ->
            model.refresh(); runCurrent()
            assertEquals(listOf("first"), model.state.value.activities.map { it.id })
            assertTrue(model.state.value.more)
            model.more(); runCurrent()
            assertEquals(listOf("first", "second"), model.state.value.activities.map { it.id })
            assertEquals(ActivityHistoryCursor(1000, "first"), repository.queries.last().before)
            model.search("Hike"); runCurrent()
            assertEquals("Hike", repository.queries.last().nameContains)
            assertEquals(50, repository.queries.last().limit)
            assertEquals(setOf(ActivityStatus.COMPLETED), repository.queries.last().statuses)
            assertNull(repository.queries.last().before)
        }
    }
    @Test fun completedActivityListUsesCatalogFilteringAndRetainsItsFilterWhileSearching() = runTest {
        fixture { model, repository ->
            model.refresh(); runCurrent()
            assertEquals(setOf(ActivityStatus.COMPLETED), repository.queries.last().statuses)
            model.search("Ride"); runCurrent()
            assertEquals(setOf(ActivityStatus.COMPLETED), repository.queries.last().statuses)
            assertEquals("Ride", repository.queries.last().nameContains)
            model.more(); runCurrent()
            assertEquals(ActivityHistoryCursor(1000, "first"), repository.queries.last().before)
        }
    }
    private suspend fun TestScope.fixture(block: suspend (HistoryViewModel, HistoryTestRepository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = HistoryTestRepository()
            val model = HistoryViewModel(repository, HistoryTestSession())
            owner.put("history", model)
            block(model, repository)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
private class HistoryTestRepository : ActivityRepository {
    var historyDelayMillis = 0L
    var failHistory = false
    private val time = ActivityTime(1000, 1000, "clock")
    var record = ActivityRecord("first", 0, "generation", "Hike", ActivityProfile.HIKING, ActivityStatus.COMPLETED,
        time, time, finishedAt = time, statistics = ActivityStatistics(distanceMeters = 10.0))
    val queries = mutableListOf<ActivityHistoryQuery>()
    val commits = mutableListOf<ActivityCommit>()
    override suspend fun history(query: ActivityHistoryQuery): ActivityResult<ActivityHistoryPage> {
        queries += query
        delay(historyDelayMillis)
        if (failHistory) error("History unavailable")
        val item = ActivitySummary(if (query.before == null) "first" else "second", record.name, record.profile, record.status,
            record.revision, 1000, 1000, null)
        return ActivityResult.Success(ActivityHistoryPage(listOf(item), if (query.before == null) ActivityHistoryCursor(1000, "first") else null))
    }
    override suspend fun get(id: String) = ActivityResult.Success(record)
    override suspend fun commit(value: ActivityCommit): ActivityResult<ActivityRecord> {
        commits += value
        record = value.record
        return ActivityResult.Success(record)
    }
    override suspend fun create(record: ActivityRecord, started: ActivityEvent): ActivityResult<ActivityRecord> = error("unused")
    override suspend fun telemetry(activityId: String, query: TelemetryQuery): ActivityResult<TelemetryPage> = error("unused")
    override suspend fun positions(activityId: String, query: ActivityPositionQuery): ActivityResult<ActivityPositionPage> = error("unused")
    override suspend fun navigationRoute(activityId: String): ActivityResult<ActivityRoute?> = error("unused")
    override suspend fun events(activityId: String, query: ActivityEventQuery): ActivityResult<ActivityEventPage> = error("unused")
    override suspend fun delete(id: String): ActivityResult<Unit> = error("unused")
    override suspend fun reconcile(): ActivityResult<Unit> = ActivityResult.Success(Unit)
    override suspend fun appliedCommand(activityId: String, commandId: String): ActivityResult<ActivityRecord?> = error("unused")
    override fun observeActive() = MutableStateFlow<ActivityRecord?>(null)
}
private class HistoryTestSession : ActivitySession {
    override val active = MutableStateFlow<ActivityRecord?>(null)
    override val live = MutableStateFlow<ActivityLiveState?>(null)
    override val navigationAlerts = MutableSharedFlow<ActivityNavigationAlert>()
    override fun initialize() = Unit
    override suspend fun navigate(command: ActivityNavigationCommand): ActivityResult<ActivityNavigationState> = error("unused")
    override suspend fun start(request: ActivityStartRequest): ActivityResult<ActivityRecord> = error("unused")
    override suspend fun apply(command: ActivityCommand): ActivityResult<ActivityRecord> = error("unused")
    override suspend fun recover() = ActivityResult.Success<ActivityRecord?>(null)
}
