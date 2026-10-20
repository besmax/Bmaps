/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.sharing.SharedDocumentStore
import bes.max.bmaps.core.sharing.SharingLocation
import bes.max.bmaps.domain.activity.exchange.ActivityExporter
import bes.max.bmaps.domain.activity.model.ActivityEvent
import bes.max.bmaps.domain.activity.model.ActivityProfile
import bes.max.bmaps.domain.activity.model.ActivityRecord
import bes.max.bmaps.domain.activity.model.ActivityResult
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.ActivityStatistics
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.ActivityTime
import bes.max.bmaps.domain.activity.model.MeasurementPhase
import bes.max.bmaps.domain.activity.model.MeasurementSource
import bes.max.bmaps.domain.activity.model.MeasurementSourceKind
import bes.max.bmaps.domain.activity.model.SpeedMeasurement
import bes.max.bmaps.domain.activity.model.TelemetrySample
import bes.max.bmaps.domain.activity.navigation.ActivityPositionPage
import bes.max.bmaps.domain.activity.navigation.ActivityPositionQuery
import bes.max.bmaps.domain.activity.repository.ActivityCommit
import bes.max.bmaps.domain.activity.repository.ActivityEventPage
import bes.max.bmaps.domain.activity.repository.ActivityEventQuery
import bes.max.bmaps.domain.activity.repository.ActivityHistoryPage
import bes.max.bmaps.domain.activity.repository.ActivityHistoryQuery
import bes.max.bmaps.domain.activity.repository.ActivityRepository
import bes.max.bmaps.domain.activity.repository.ActivityRoutePage
import bes.max.bmaps.domain.activity.repository.ActivityRouteQuery
import bes.max.bmaps.domain.activity.repository.ActivityRouteRepository
import bes.max.bmaps.domain.activity.repository.TelemetryPage
import bes.max.bmaps.domain.activity.repository.TelemetryQuery
import bmaps.feature.viewer.generated.resources.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailsTest {
    @Test fun summaryUsesRecordedDurationAndFullSpeedSeriesWithoutInventingHeartRate() = runTest {
        val repository = DetailsTestRepository()
        val state = ActivityReviewReader(repository).read("activity")
        assertEquals("2.0", state.averageSpeed)
        assertEquals("36.0", state.maximumSpeed)
        assertEquals(Res.string.track_road, state.profile)
        assertEquals("map", state.packageId)
        assertFalse(state.heartRateAvailable)
        assertEquals("1:00:00", state.elapsed)
        assertEquals("0:30:00", state.recording)
        repository.samples = emptyList()
        repository.record = repository.record.copy(statistics = ActivityStatistics())
        val empty = ActivityReviewReader(repository).read("activity")
        assertNull(empty.averageSpeed)
        assertNull(empty.maximumSpeed)
    }

    @Test fun chartValueIsAvailableDuringDelayedLookupAndRapidReselection() = runTest {
        fixture { model, repository ->
            repository.telemetryDelay = 1000
            model.selectTime(1000, "fast")
            assertEquals("36.0 km/h", model.state.value.selectedValue)
            runCurrent()
            assertEquals("36.0 km/h", model.state.value.selectedValue)
            advanceTimeBy(500)
            model.selectTime(1000, "slow")
            assertEquals("3.6 km/h", model.state.value.selectedValue)
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals("3.6 km/h", model.state.value.selectedValue)
            advanceTimeBy(500)
            runCurrent()
            assertEquals("3.6 km/h", model.state.value.selectedValue)
            assertEquals(1000L, model.state.value.selectedTime)
            assertNull(model.state.value.selectedPosition)
        }
    }

    @Test fun invalidRenameStaysVisibleAndValidRenameKeepsRecordingAndSelectedCursor() = runTest {
        fixture { model, repository ->
            val original = repository.record
            model.selectTime(2000)
            runCurrent()
            model.renameDialog(true)
            model.draftName("  ")
            model.rename()
            runCurrent()
            assertEquals(Res.string.history_invalid_name, model.state.value.actionError)
            assertTrue(model.state.value.renaming)
            assertTrue(repository.commits.isEmpty())
            model.draftName(" Renamed ")
            model.rename()
            runCurrent()
            assertEquals("Renamed", model.state.value.name)
            assertFalse(model.state.value.renaming)
            assertEquals(2000L, model.state.value.selectedTime)
            assertEquals(original.statistics, repository.record.statistics)
            assertEquals(original.updatedAt, repository.record.updatedAt)
            assertEquals(original.finishedAt, repository.record.finishedAt)
            assertEquals(original.revision + 1, repository.record.revision)
        }
    }

    @Test fun deletionRequiresConfirmationAndRejectsAChangedRevision() = runTest {
        fixture { model, repository ->
            model.delete()
            runCurrent()
            assertEquals(0, repository.deletions)
            model.deleteDialog(true)
            repository.record = repository.record.copy(revision = repository.record.revision + 1)
            model.delete()
            runCurrent()
            assertEquals(Res.string.history_changed, model.state.value.actionError)
            assertEquals(0, repository.deletions)
            assertTrue(model.state.value.deleting)
            assertFalse(model.state.value.deleted)
        }
    }

    @Test fun successfulDeletionClosesDetailsWithoutChangingTheSession() = runTest {
        fixture { model, repository ->
            model.deleteDialog(true)
            model.delete()
            runCurrent()
            assertEquals(1, repository.deletions)
            assertTrue(model.state.value.deleted)
            assertFalse(model.state.value.deleting)
        }
    }

    private suspend fun TestScope.fixture(block: suspend (ReviewViewModel, DetailsTestRepository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val repository = DetailsTestRepository()
            val routes = DetailsTestRoutes()
            val model = ReviewViewModel(ActivityReviewReader(repository), repository, ActivityExporter(repository, routes),
                SharedDocumentStore(SharingLocation("unused-details-test")))
            owner.put("details", model)
            model.open("activity")
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { !it.loading && it.name.isNotEmpty() } } }
            block(model, repository)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

private class DetailsTestRepository : ActivityRepository {
    private val started = ActivityTime(1000, 1000, "clock")
    private val finished = ActivityTime(3_601_000, 3_601_000, "clock")
    var record = ActivityRecord("activity", 0, "generation", "Ride", ActivityProfile.ROAD_TRAVEL,
        ActivityStatus.COMPLETED, started, finished, finishedAt = finished, packageId = "map",
        statistics = ActivityStatistics(1000.0, 3_600_000, 1_800_000, 1_800_000, 0))
    var samples = listOf(sample("slow", 1.0), sample("fast", 10.0))
    val commits = mutableListOf<ActivityCommit>()
    var deletions = 0
    var telemetryDelay = 0L
    private fun sample(id: String, speed: Double) = TelemetrySample(id,
        MeasurementSource("phone", MeasurementSourceKind.PHONE_LOCATION), started, started,
        MeasurementPhase.RECORDING, SpeedMeasurement(speed))
    override suspend fun get(id: String) = ActivityResult.Success(record)
    override suspend fun commit(value: ActivityCommit): ActivityResult<ActivityRecord> {
        commits += value
        record = value.record
        return ActivityResult.Success(record)
    }
    override suspend fun telemetry(activityId: String, query: TelemetryQuery): ActivityResult<TelemetryPage> {
        delay(telemetryDelay)
        return ActivityResult.Success(TelemetryPage(samples, null))
    }
    override suspend fun positions(activityId: String, query: ActivityPositionQuery) = ActivityResult.Success(ActivityPositionPage(emptyList(), null))
    override suspend fun events(activityId: String, query: ActivityEventQuery) = ActivityResult.Success(ActivityEventPage(emptyList(), null))
    override suspend fun delete(id: String): ActivityResult<Unit> { deletions++; return ActivityResult.Success(Unit) }
    override suspend fun history(query: ActivityHistoryQuery): ActivityResult<ActivityHistoryPage> = error("unused")
    override suspend fun navigationRoute(activityId: String): ActivityResult<ActivityRoute?> = error("unused")
    override suspend fun create(record: ActivityRecord, started: ActivityEvent): ActivityResult<ActivityRecord> = error("unused")
    override suspend fun reconcile(): ActivityResult<Unit> = error("unused")
    override suspend fun appliedCommand(activityId: String, commandId: String): ActivityResult<ActivityRecord?> = error("unused")
    override fun observeActive() = MutableStateFlow<ActivityRecord?>(null)
}

private class DetailsTestRoutes : ActivityRouteRepository {
    override suspend fun get(id: String): ActivityResult<ActivityRoute> = error("unused")
    override suspend fun save(route: ActivityRoute, expectedRevision: Long?): ActivityResult<ActivityRoute> = error("unused")
    override suspend fun derive(activityId: String, routeId: String): ActivityResult<ActivityRoute?> = error("unused")
    override suspend fun delete(id: String): ActivityResult<Unit> = error("unused")
    override suspend fun routes(query: ActivityRouteQuery): ActivityResult<ActivityRoutePage> = error("unused")
    override suspend fun saveImported(routes: List<ActivityRoute>): ActivityResult<Unit> = error("unused")
}
