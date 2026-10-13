/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import bes.max.bmaps.core.database.ActivityCatalog
import bes.max.bmaps.core.database.PackageDatabase
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.storage.ActivityFileStorage
import bes.max.bmaps.core.storage.ActivityStorageLocation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityStorageScenarios(private val database: (String) -> PackageDatabase) {
    suspend fun ownerStatisticsAndCommandPayloadSurviveReopen() = fixture { f ->
        val initial = f.activities.create(record("statistics"), started()).success()
        val command = ActivityCommand("pause-owner", initial.id, initial.sessionGeneration, 0, ActivityAction.PAUSE)
        val statistics = ActivityStatistics(distanceMeters = 12.5, elapsedMillis = 1_000, recordingMillis = 1_000, movingMillis = 500)
        val paused = initial.copy(revision = 1, controlRevision = 1, status = ActivityStatus.PAUSED, updatedAt = time(2000),
            statistics = statistics, lastCommand = command)
        f.activities.commit(ActivityCommit(0, paused, events = listOf(ActivityEvent("pause", 2, ActivityEventKind.PAUSED, time(2000))), appliedCommandId = command.id)).success()
        f.reopen()
        assertEquals(statistics, f.activities.get(initial.id).success().statistics)
        assertEquals(command, f.activities.appliedCommand(initial.id, command.id).success()?.lastCommand)
        assertEquals(paused, f.activities.observeActive().first())
    }

    suspend fun navigationSnapshotAndOrderedPositionsSurviveReopen() = fixture { f ->
        var record = f.activities.create(record("navigation"), started()).success()
        record = f.activities.commit(ActivityCommit(record.revision, record.copy(revision = 1, updatedAt = time(1200)),
            listOf(position("p1", 1100, "path", 50.0), position("p2", 1200, "path", 50.01)))).success()
        val snapshot = line("return", "Return")
        val point = RoutePosition(0, 0, 1.0)
        val nav = ActivityNavigationState(ActivityNavigationMode.RECORDED_RETURN, snapshot.id, 0, RouteDirection.REVERSE, point, point, revision = 1)
        val command = ActivityNavigationCommand("return-command", record.id, record.sessionGeneration, 0, ActivityNavigationAction.RETURN_ALONG_RECORDING)
        val next = record.copy(revision = 2, updatedAt = time(1300), navigation = nav, lastNavigationCommand = command)
        val commit = ActivityCommit(record.revision, next, events = listOf(ActivityEvent("return-event", 2, ActivityEventKind.RETURN_STARTED, time(1300), navigationRouteId = snapshot.id)),
            appliedCommandId = command.id, navigationSnapshot = snapshot)
        f.activities.commit(commit).success()
        assertEquals(next, f.activities.commit(commit).success())
        f.reopen()
        assertEquals(snapshot, f.activities.navigationRoute(record.id).success())
        assertEquals(command, f.activities.appliedCommand(record.id, command.id).success()?.lastNavigationCommand)
        val first = f.activities.positions(record.id, ActivityPositionQuery(limit = 1)).success()
        val second = f.activities.positions(record.id, ActivityPositionQuery(first.nextSequence!!, 1)).success()
        assertEquals(listOf("p1", "p2"), (first.items + second.items).map { it.id })
        assertNull(second.nextSequence)
        assertNotNull(second.lastSequence)
        assertTrue(second.lastSequence!! > first.lastSequence!!)
        val emptyTail = f.activities.positions(record.id, ActivityPositionQuery(second.lastSequence!!, 1)).success()
        assertTrue(emptyTail.items.isEmpty())
        assertNull(emptyTail.lastSequence)
        assertTrue(f.routes.routes(ActivityRouteQuery()).success().items.isEmpty())
        val path = Path(f.root, "activities", "activity.sqlite").toString()
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("PRAGMA user_version").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
            db.prepare("CREATE TRIGGER fail_navigation BEFORE UPDATE ON activities BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END").use { it.step() }
        }
        val failedSnapshot = snapshot.copy(id = "failed-return")
        val failedCommand = command.copy(id = "failed-command", expectedNavigationRevision = 1)
        assertIs<ActivityResult.Failure>(f.activities.commit(ActivityCommit(2, next.copy(revision = 3, updatedAt = time(1400),
            navigation = nav.copy(routeId = failedSnapshot.id, revision = 2), lastNavigationCommand = failedCommand),
            events = listOf(ActivityEvent("failed-event", 3, ActivityEventKind.RETURN_STARTED, time(1400), navigationRouteId = failedSnapshot.id)),
            appliedCommandId = failedCommand.id, navigationSnapshot = failedSnapshot)))
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("SELECT count(*) FROM navigation_snapshots WHERE id='failed-return'").use { assertTrue(it.step()); assertEquals(0L, it.getLong(0)) }
            db.prepare("DROP TRIGGER fail_navigation").use { it.step() }
        }
        assertEquals(next, f.activities.get(record.id).success())
        val interrupted = next.copy(revision = 3, controlRevision = 1, status = ActivityStatus.INTERRUPTED, updatedAt = time(1400))
        f.activities.commit(ActivityCommit(2, interrupted, events = listOf(ActivityEvent("interrupted", 3, ActivityEventKind.INTERRUPTED, time(1400))))).success()
        f.activities.delete(record.id).success()
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("SELECT count(*) FROM navigation_snapshots").use { assertTrue(it.step()); assertEquals(0L, it.getLong(0)) }
        }
    }

    suspend fun singleOwnerAndRestart() = fixture { f ->
        val results = coroutineScope {
            listOf(async { f.activities.create(record("a"), started()) }, async { f.activities.create(record("b"), started()) }).map { it.await() }
        }
        assertEquals(1, results.count { it is ActivityResult.Success })
        assertEquals(ActivityFailure.AnotherActivityActive, assertIs<ActivityResult.Failure>(results.first { it is ActivityResult.Failure }).reason)
        val active = assertNotNull(f.activities.observeActive().first())
        f.reopen()
        assertEquals(active, f.activities.observeActive().first())
        assertEquals(1, f.activities.history(ActivityHistoryQuery()).success().items.size)
    }

    suspend fun atomicRollbackAndIdempotentCommands() = fixture { f ->
        val initial = f.activities.create(record("activity"), started()).success()
        val event = ActivityEvent("duplicate", 2, ActivityEventKind.CONTROL_POINT, time(1100))
        assertIs<ActivityResult.Failure>(f.activities.commit(ActivityCommit(0, initial.copy(revision = 1, updatedAt = time(1200)),
            events = listOf(event, event.copy(sequence = 3)))))
        f.reopen()
        assertEquals(0L, f.activities.get(initial.id).success().revision)
        assertEquals(listOf(1L), f.activities.events(initial.id, ActivityEventQuery()).success().items.map { it.sequence })
        val sample = position("p1", 1100, "segment", 50.0)
        val commit = ActivityCommit(0, initial.copy(revision = 1, updatedAt = time(1200)), listOf(sample, sample), appliedCommandId = "write-1")
        assertEquals(1L, f.activities.commit(commit).success().revision)
        assertEquals(1L, f.activities.commit(commit).success().revision)
        assertEquals(1, f.activities.telemetry(initial.id, TelemetryQuery(0, 5000)).success().items.size)
        assertEquals(1L, f.activities.appliedCommand(initial.id, "write-1").success()?.revision)
        assertEquals(ActivityFailure.RevisionConflict, assertIs<ActivityResult.Failure>(f.activities.commit(commit.copy(appliedCommandId = null))).reason)
        f.reopen()
        assertEquals(1L, f.activities.appliedCommand(initial.id, "write-1").success()?.revision)
    }

    suspend fun pauseIntervalsGapsAndDerivedRoutes() = fixture { f ->
        var current = f.activities.create(record("activity"), started()).success()
        current = f.activities.commit(ActivityCommit(0, current.copy(revision = 1, updatedAt = time(1200)),
            listOf(position("p1", 1100, "out", 50.0), position("p2", 1200, "out", 50.01)))).success()
        current = transition(f, current, ActivityStatus.PAUSED, ActivityEventKind.PAUSED, 2000)
        f.reopen()
        current = transition(f, current, ActivityStatus.RECORDING, ActivityEventKind.RESUMED, 5000)
        val paused = TelemetrySample("hr-paused", MeasurementSource("watch", MeasurementSourceKind.WATCH_HEALTH), time(3000), time(5100),
            MeasurementPhase.RECORDING, HeartRateMeasurement(120.0))
        assertIs<ActivityResult.Failure>(f.activities.commit(ActivityCommit(current.revision, current.copy(revision = current.revision + 1,
            updatedAt = time(5100)), listOf(paused))))
        assertIs<ActivityResult.Failure>(f.activities.commit(ActivityCommit(current.revision, current.copy(revision = current.revision + 1,
            updatedAt = time(5100)), listOf(position("bad-gap", 5100, "out", 50.1)))))
        current = f.activities.commit(ActivityCommit(current.revision, current.copy(revision = current.revision + 1, updatedAt = time(5200)),
            listOf(position("p3", 5100, "return", 50.1), position("p4", 5200, "return", 50.2),
                paused.copy(id = "hr-before-pause", acquiredAt = time(1300), receivedAt = time(5200))))).success()
        current = transition(f, current, ActivityStatus.FINALIZING, ActivityEventKind.FINISH_REQUESTED, 6000)
        val derived = assertNotNull(f.routes.derive(current.id, "derived").success())
        assertEquals(listOf(2, 2), derived.segments.map { it.vertices.size })
        assertEquals(derived, f.routes.derive(current.id, "other-id").success())
        current = transition(f, current.copy(derivedRouteId = derived.id), ActivityStatus.COMPLETED, ActivityEventKind.COMPLETED, 6100)
        assertNull(f.activities.observeActive().first())
        val ids = mutableListOf<String>()
        var cursor: TelemetryCursor? = null
        do {
            val page = f.activities.telemetry(current.id, TelemetryQuery(0, 10_000, cursor, 2)).success()
            ids += page.items.map { it.id }
            cursor = page.next
        } while (cursor != null)
        assertEquals(5, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertTrue("hr-paused" !in ids)
        f.activities.delete(current.id).success()
        assertEquals(derived, f.routes.get(derived.id).success())
        assertEquals(ActivityFailure.NotFound, assertIs<ActivityResult.Failure>(f.activities.create(record(current.id), started())).reason)
    }

    suspend fun routeSnapshotsAndImportRollback() = fixture { f ->
        val source = line("route", "Original")
        f.routes.save(source, null).success()
        val selected = f.activities.create(record("activity").copy(route = RouteSelection(source, RouteDirection.REVERSE, RoutePosition(0, 0, 1.0))), started()).success()
        f.routes.save(source.copy(revision = 1, name = "Edited"), 0).success()
        assertEquals("Original", f.activities.get(selected.id).success().route?.snapshot?.name)
        val imported = f.routes.saveImported(listOf(line("new-route", "New"), source))
        assertEquals(ActivityFailure.RevisionConflict, assertIs<ActivityResult.Failure>(imported).reason)
        assertEquals(ActivityFailure.NotFound, assertIs<ActivityResult.Failure>(f.routes.get("new-route")).reason)
        f.routes.delete(source.id).success()
        f.reopen()
        assertEquals(source, f.activities.get(selected.id).success().route?.snapshot)
    }

    suspend fun catalogRecoveryAndPackageIsolation() = fixture { f ->
        val record = f.activities.create(record("activity"), started()).success()
        val route = line("route", "Saved")
        f.routes.save(route, null).success()
        ActivityCatalog(f.db).records.deleteActivity(record.id)
        ActivityCatalog(f.db).records.deleteRoute(route.id)
        val packages = Path(f.root, "packages")
        SystemFileSystem.createDirectories(packages)
        SystemFileSystem.delete(packages)
        f.reopen()
        assertEquals(record.id, f.activities.history(ActivityHistoryQuery()).success().items.single().id)
        assertEquals(route.id, f.routes.routes(ActivityRouteQuery()).success().items.single().id)
        assertEquals(record, f.activities.get(record.id).success())
        assertTrue(SystemFileSystem.exists(Path(f.root, "activities", "activity.sqlite")))
        assertTrue(SystemFileSystem.exists(Path(f.root, "routes", "routes.sqlite")))
    }

    suspend fun boundedBatchAndHistoryCursor() = fixture { f ->
        var current = f.activities.create(record("a"), started()).success()
        val oversized = List(ActivityLimits.MAX_WRITE_SAMPLES + 1) { position("p$it", 1100L + it, "segment", 50.0 + it * 0.00001) }
        assertIs<ActivityResult.Failure>(f.activities.commit(ActivityCommit(0, current.copy(revision = 1, updatedAt = time(2000)), oversized)))
        assertEquals(0L, f.activities.get(current.id).success().revision)
        current = transition(f, current, ActivityStatus.FINALIZING, ActivityEventKind.FINISH_REQUESTED, 2000)
        assertNull(f.routes.derive(current.id, "empty-route").success())
        transition(f, current, ActivityStatus.COMPLETED, ActivityEventKind.COMPLETED, 2100)
        f.activities.create(record("b"), started()).success()
        val first = f.activities.history(ActivityHistoryQuery(limit = 1)).success()
        val second = f.activities.history(ActivityHistoryQuery(before = first.next, limit = 1)).success()
        assertEquals("a", first.items.single().id)
        assertEquals("b", second.items.single().id)
        assertNull(second.next)
        assertEquals(listOf("a"), f.activities.history(ActivityHistoryQuery(statuses = setOf(ActivityStatus.COMPLETED))).success().items.map { it.id })
    }

    suspend fun wallClockRollbackKeepsPositionOrder() = fixture { f ->
        val startTime = ActivityTime(10_000, 1000, "clock-1")
        var current = f.activities.create(record("activity").copy(startedAt = startTime, updatedAt = startTime), started().copy(time = startTime)).success()
        val first = position("p1", 10_100, "segment", 50.0).copy(acquiredAt = ActivityTime(10_100, 1100, "clock-1"))
        current = f.activities.commit(ActivityCommit(0, current.copy(revision = 1, updatedAt = first.acquiredAt), listOf(first))).success()
        val second = position("p2", 9900, "segment", 50.01).copy(acquiredAt = ActivityTime(9900, 1200, "clock-1"))
        f.activities.commit(ActivityCommit(current.revision, current.copy(revision = current.revision + 1, updatedAt = second.acquiredAt), listOf(second))).success()
        f.reopen()
        assertEquals(2, f.activities.telemetry(current.id, TelemetryQuery(0, 20_000)).success().items.size)
    }

    private suspend fun transition(f: Fixture, record: ActivityRecord, status: ActivityStatus, kind: ActivityEventKind, epoch: Long): ActivityRecord {
        val sequence = f.activities.events(record.id, ActivityEventQuery()).success().items.last().sequence + 1
        val time = time(epoch)
        val next = record.copy(revision = record.revision + 1, controlRevision = record.controlRevision + 1,
            updatedAt = time, status = status, finishedAt = if (status == ActivityStatus.FINALIZING) time else record.finishedAt)
        return f.activities.commit(ActivityCommit(record.revision, next, events = listOf(ActivityEvent("event-$sequence", sequence, kind, time)))).success()
    }

    private fun record(id: String) = ActivityRecord(id, 0, "generation-1", "Activity $id", ActivityProfile.HIKING,
        ActivityStatus.RECORDING, time(1000), time(1000))
    private fun started() = ActivityEvent("start", 1, ActivityEventKind.STARTED, time(1000))
    private fun time(epoch: Long) = ActivityTime(epoch, epoch, "clock-1")
    private fun position(id: String, epoch: Long, segment: String, latitude: Double) = TelemetrySample(id,
        MeasurementSource("gps", MeasurementSourceKind.PHONE_LOCATION), time(epoch), time(epoch), MeasurementPhase.RECORDING,
        PositionMeasurement(GeographicCoordinate(latitude, 30.0), 5.0), segment)
    private fun line(id: String, name: String) = ActivityRouteAdapter.line(id, name,
        listOf(RouteVertex(GeographicCoordinate(50.0, 30.0)), RouteVertex(GeographicCoordinate(50.01, 30.01))))

    private inner class Fixture(val root: Path) {
        var db = database(Path(root, "catalog.db").toString())
        lateinit var activities: LocalActivityRepository
        lateinit var routes: LocalActivityRouteRepository
        init { repositories() }
        private fun repositories() {
            val store = LocalActivityStore(ActivityFileStorage(ActivityStorageLocation(root.toString())), ActivityCatalog(db))
            activities = LocalActivityRepository(store)
            routes = LocalActivityRouteRepository(store)
        }
        fun reopen() { db.close(); db = database(Path(root, "catalog.db").toString()); repositories() }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val root = Path(SystemTemporaryDirectory, "bmaps-activity-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(root)
        val f = Fixture(root)
        try { block(f) } finally {
            f.db.close()
            fun delete(path: Path) {
                if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::delete)
                SystemFileSystem.delete(path)
            }
            delete(root)
        }
    }
}

private fun <T> ActivityResult<T>.success(): T = assertIs<ActivityResult.Success<T>>(this).value
