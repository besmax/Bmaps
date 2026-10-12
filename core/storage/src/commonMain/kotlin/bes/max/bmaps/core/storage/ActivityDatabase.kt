/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class StoredActivity(val id: String, val revision: Long, val generation: String, val startedAt: Long, val payload: String, val routeId: String? = null)
data class StoredActivityEvent(
    val id: String, val sequence: Long, val acquiredAt: Long, val payload: String, val kind: String,
    val monotonicTime: Long? = null, val clockId: String = "",
)
data class StoredTelemetry(
    val id: String, val sourceId: String, val acquiredAt: Long, val kind: String,
    val segmentId: String?, val payload: String, val sequence: Long = 0,
)
data class StoredActivityCheckpoint(
    val eventSequence: Long = 0,
    val positionTime: Long? = null,
    val segmentId: String? = null,
    val segmentBreakRequired: Boolean = false,
    val positionMonotonic: Long? = null,
    val positionClockId: String? = null,
)

class ActivityDatabase internal constructor(private val sql: SpatialSql) {
    fun get(id: String): StoredActivity? = sql.query(
        "SELECT id, revision, generation, started_at, substr(payload,1,65537), route_id FROM activities WHERE id=?", arrayOf<Any?>(id), ::activity,
    ).singleOrNull()

    fun page(afterId: String, limit: Int): List<StoredActivity> {
        require(limit in 1..500)
        return sql.query("SELECT id, revision, generation, started_at, substr(payload,1,65537), route_id FROM activities WHERE id>? ORDER BY id LIMIT ?",
            arrayOf<Any?>(afterId, limit), ::activity)
    }

    fun active(): StoredActivity? = sql.query(
        "SELECT a.id, a.revision, a.generation, a.started_at, substr(a.payload,1,65537), a.route_id FROM activities a JOIN activity_owner o ON o.activity_id=a.id", read = ::activity,
    ).singleOrNull()

    fun snapshot(id: String): String? = sql.query("SELECT substr(payload,1,64000001) FROM route_snapshots WHERE activity_id=?", arrayOf<Any?>(id)) {
        boundedText(it, 0, 64_000_000)
    }.singleOrNull()

    fun checkpoint(id: String): StoredActivityCheckpoint = sql.query(
        "SELECT event_sequence, position_time, segment_id, segment_break, position_monotonic, position_clock FROM checkpoints WHERE activity_id=?", arrayOf<Any?>(id),
    ) {
        StoredActivityCheckpoint(it.getLong(0), if (it.isNull(1)) null else it.getLong(1),
            if (it.isNull(2)) null else it.getText(2), it.getLong(3) != 0L,
            if (it.isNull(4)) null else it.getLong(4), if (it.isNull(5)) null else it.getText(5))
    }.singleOrNull() ?: throw SpatialNotFound()

    fun commandResult(activityId: String, commandId: String): String? = sql.query(
        "SELECT substr(result,1,65537) FROM applied_commands WHERE activity_id=? AND id=?", arrayOf<Any?>(activityId, commandId),
    ) { boundedText(it, 0, 65_536) }.singleOrNull()

    suspend fun create(record: StoredActivity, snapshot: String?, event: StoredActivityEvent, fingerprint: String): StoredActivity = sql.transaction {
        if (sql.scalar("SELECT count(*) FROM activity_tombstones WHERE id=?", record.id) != 0L) throw SpatialNotFound()
        val existing = get(record.id)
        if (existing != null) {
            val original = sql.query("SELECT creation_fingerprint FROM activities WHERE id=?", arrayOf<Any?>(record.id)) { it.getText(0) }.single()
            if (original != fingerprint) throw SpatialRevisionConflict()
            return@transaction existing
        }
        if (active() != null) throw SpatialOwnerBusy()
        require(record.payload.encodeToByteArray().size <= 65_536 && record.revision == 0L)
        sql.execute("INSERT INTO activities(id, revision, generation, started_at, payload, creation_fingerprint, route_id) VALUES(?, ?, ?, ?, ?, ?, ?)",
            record.id, record.revision, record.generation, record.startedAt, record.payload, fingerprint, record.routeId)
        sql.execute("INSERT INTO activity_owner(slot, activity_id) VALUES(1, ?)", record.id)
        if (snapshot != null) {
            require(snapshot.encodeToByteArray().size <= 64_000_000)
            sql.execute("INSERT INTO route_snapshots(activity_id, payload) VALUES(?, ?)", record.id, snapshot)
        }
        addEvent(record.id, event)
        sql.execute("INSERT INTO checkpoints(activity_id, event_sequence, segment_break) VALUES(?, ?, 0)", record.id, event.sequence)
        record
    }

    suspend fun commit(
        record: StoredActivity, expectedRevision: Long, events: List<StoredActivityEvent>, samples: List<StoredTelemetry>,
        nextCheckpoint: StoredActivityCheckpoint, ownsActivity: Boolean, commandId: String?, fingerprint: String,
    ): StoredActivity = sql.transaction {
        val current = get(record.id) ?: throw SpatialNotFound()
        if (commandId != null) {
            val previous = sql.query("SELECT fingerprint, substr(result,1,65537), result_revision, result_generation FROM applied_commands WHERE activity_id=? AND id=?",
                arrayOf<Any?>(record.id, commandId)) { Triple(it.getText(0), boundedText(it, 1, 65_536), it.getLong(2) to it.getText(3)) }.singleOrNull()
            if (previous != null) {
                if (previous.first != fingerprint) throw SpatialRevisionConflict()
                return@transaction current.copy(payload = previous.second, revision = previous.third.first, generation = previous.third.second)
            }
        }
        if (current.revision != expectedRevision || record.revision != expectedRevision + 1 || expectedRevision == Long.MAX_VALUE) throw SpatialRevisionConflict()
        require(record.payload.encodeToByteArray().size <= 65_536 && events.size <= 256 && samples.size <= 256)
        var sequence = checkpoint(record.id).eventSequence
        for (event in events) {
            currentCoroutineContext().ensureActive()
            if (event.sequence <= sequence) throw SpatialCorruptData()
            addEvent(record.id, event)
            sequence = event.sequence
        }
        if (sequence != nextCheckpoint.eventSequence) throw SpatialCorruptData()
        for (sample in samples) {
            currentCoroutineContext().ensureActive()
            require(sample.payload.encodeToByteArray().size <= 65_536)
            val previous = sample(record.id, sample.sourceId, sample.id)
            if (previous != null) {
                if (previous.payload != sample.payload) throw SpatialCorruptData()
            } else sql.execute("INSERT INTO telemetry(activity_id, id, source_id, acquired_at, kind, segment_id, payload) VALUES(?, ?, ?, ?, ?, ?, ?)",
                record.id, sample.id, sample.sourceId, sample.acquiredAt, sample.kind, sample.segmentId, sample.payload)
        }
        sql.execute("UPDATE activities SET revision=?, generation=?, payload=? WHERE id=?", record.revision, record.generation, record.payload, record.id)
        sql.execute("UPDATE checkpoints SET event_sequence=?, position_time=?, segment_id=?, segment_break=?, position_monotonic=?, position_clock=? WHERE activity_id=?",
            nextCheckpoint.eventSequence, nextCheckpoint.positionTime, nextCheckpoint.segmentId, if (nextCheckpoint.segmentBreakRequired) 1 else 0,
            nextCheckpoint.positionMonotonic, nextCheckpoint.positionClockId, record.id)
        if (ownsActivity) {
            val owner = active()
            if (owner != null && owner.id != record.id) throw SpatialOwnerBusy()
            sql.execute("INSERT OR REPLACE INTO activity_owner(slot, activity_id) VALUES(1, ?)", record.id)
        } else sql.execute("DELETE FROM activity_owner WHERE activity_id=?", record.id)
        if (commandId != null) sql.execute("INSERT INTO applied_commands(activity_id, id, fingerprint, result, result_revision, result_generation) VALUES(?, ?, ?, ?, ?, ?)",
            record.id, commandId, fingerprint, record.payload, record.revision, record.generation)
        record
    }

    fun sample(activityId: String, sourceId: String, id: String): StoredTelemetry? = sql.query(
        "SELECT id, source_id, acquired_at, kind, segment_id, substr(payload,1,65537), sequence FROM telemetry WHERE activity_id=? AND source_id=? AND id=?",
        arrayOf<Any?>(activityId, sourceId, id), ::telemetry,
    ).singleOrNull()

    fun samples(activityId: String, from: Long, to: Long, afterTime: Long, afterSource: String, afterId: String, first: Boolean, limit: Int): List<StoredTelemetry> {
        require(limit in 1..501)
        return sql.query("""
            SELECT id, source_id, acquired_at, kind, segment_id, substr(payload,1,65537), sequence FROM telemetry
            WHERE activity_id=? AND acquired_at>=? AND acquired_at<=? AND
            (?=1 OR acquired_at>? OR (acquired_at=? AND source_id>?) OR (acquired_at=? AND source_id=? AND id>?))
            ORDER BY acquired_at, source_id, id LIMIT ?
        """.trimIndent(), arrayOf<Any?>(activityId, from, to, if (first) 1 else 0, afterTime, afterTime, afterSource, afterTime, afterSource, afterId, limit), ::telemetry)
    }

    fun positions(activityId: String, afterSequence: Long, limit: Int): List<StoredTelemetry> {
        require(limit in 1..500)
        return sql.query("SELECT id, source_id, acquired_at, kind, segment_id, substr(payload,1,65537), sequence FROM telemetry WHERE activity_id=? AND kind='position' AND sequence>? ORDER BY sequence LIMIT ?",
            arrayOf<Any?>(activityId, afterSequence, limit), ::telemetry)
    }

    fun hasSegment(activityId: String, segmentId: String): Boolean =
        sql.scalar("SELECT count(*) FROM telemetry WHERE activity_id=? AND segment_id=? AND kind='position'", activityId, segmentId) != 0L

    fun events(activityId: String, afterSequence: Long, limit: Int): List<StoredActivityEvent> {
        require(limit in 1..501)
        return sql.query("SELECT id, sequence, acquired_at, substr(payload,1,65537), kind, monotonic_time, clock_id FROM activity_events WHERE activity_id=? AND sequence>? ORDER BY sequence LIMIT ?",
            arrayOf<Any?>(activityId, afterSequence, limit), ::event)
    }

    fun lifecycleAt(activityId: String, acquiredAt: Long, monotonicTime: Long?, clockId: String): StoredActivityEvent? {
        val select = "SELECT id, sequence, acquired_at, substr(payload,1,65537), kind, monotonic_time, clock_id FROM activity_events WHERE activity_id=? AND kind IN ('STARTED','PAUSED','RESUMED','INTERRUPTED','FINISH_REQUESTED','COMPLETED')"
        if (monotonicTime != null) {
            val local = sql.query("$select AND clock_id=? AND monotonic_time<=? ORDER BY sequence DESC LIMIT 1",
                arrayOf<Any?>(activityId, clockId, monotonicTime), ::event).singleOrNull()
            if (local != null) return local
        }
        return sql.query("$select AND acquired_at<=? ORDER BY sequence DESC LIMIT 1", arrayOf<Any?>(activityId, acquiredAt), ::event).singleOrNull()
    }

    suspend fun delete(id: String) = sql.transaction {
        if (get(id) == null) throw SpatialNotFound()
        sql.execute("INSERT OR IGNORE INTO activity_tombstones(id) VALUES(?)", id)
        sql.execute("DELETE FROM activities WHERE id=?", id)
    }

    private fun addEvent(id: String, event: StoredActivityEvent) {
        require(event.payload.encodeToByteArray().size <= 65_536)
        sql.execute("INSERT INTO activity_events(activity_id, id, sequence, acquired_at, payload, kind, monotonic_time, clock_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            id, event.id, event.sequence, event.acquiredAt, event.payload, event.kind, event.monotonicTime, event.clockId)
    }

    companion object {
        internal val SCHEMA = listOf(
            "CREATE TABLE activities(id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, generation TEXT NOT NULL, started_at INTEGER NOT NULL, payload TEXT NOT NULL, creation_fingerprint TEXT NOT NULL, route_id TEXT)",
            "CREATE TABLE activity_owner(slot INTEGER PRIMARY KEY CHECK(slot=1), activity_id TEXT NOT NULL UNIQUE REFERENCES activities(id) ON DELETE CASCADE)",
            "CREATE TABLE route_snapshots(activity_id TEXT PRIMARY KEY NOT NULL REFERENCES activities(id) ON DELETE CASCADE, payload TEXT NOT NULL)",
            "CREATE TABLE checkpoints(activity_id TEXT PRIMARY KEY NOT NULL REFERENCES activities(id) ON DELETE CASCADE, event_sequence INTEGER NOT NULL, position_time INTEGER, segment_id TEXT, segment_break INTEGER NOT NULL, position_monotonic INTEGER, position_clock TEXT)",
            "CREATE TABLE activity_events(activity_id TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE, id TEXT NOT NULL, sequence INTEGER NOT NULL, acquired_at INTEGER NOT NULL, payload TEXT NOT NULL, kind TEXT NOT NULL, monotonic_time INTEGER, clock_id TEXT NOT NULL, PRIMARY KEY(activity_id, sequence), UNIQUE(activity_id, id))",
            "CREATE TABLE telemetry(sequence INTEGER PRIMARY KEY AUTOINCREMENT, activity_id TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE, id TEXT NOT NULL, source_id TEXT NOT NULL, acquired_at INTEGER NOT NULL, kind TEXT NOT NULL, segment_id TEXT, payload TEXT NOT NULL, UNIQUE(activity_id, source_id, id))",
            "CREATE INDEX telemetry_time ON telemetry(activity_id, acquired_at, source_id, id)",
            "CREATE INDEX telemetry_path ON telemetry(activity_id, kind, sequence)",
            "CREATE INDEX telemetry_segment ON telemetry(activity_id, segment_id)",
            "CREATE TABLE applied_commands(activity_id TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE, id TEXT NOT NULL, fingerprint TEXT NOT NULL, result TEXT NOT NULL, result_revision INTEGER NOT NULL, result_generation TEXT NOT NULL, PRIMARY KEY(activity_id, id))",
            "CREATE TABLE activity_tombstones(id TEXT PRIMARY KEY NOT NULL)",
        )

        private fun activity(row: SQLiteStatement) = StoredActivity(row.getText(0), row.getLong(1), row.getText(2), row.getLong(3), boundedText(row, 4, 65_536), if (row.isNull(5)) null else row.getText(5))
        private fun event(row: SQLiteStatement) = StoredActivityEvent(row.getText(0), row.getLong(1), row.getLong(2), boundedText(row, 3, 65_536), row.getText(4),
            if (row.isNull(5)) null else row.getLong(5), row.getText(6))
        private fun telemetry(row: SQLiteStatement) = StoredTelemetry(row.getText(0), row.getText(1), row.getLong(2), row.getText(3),
            if (row.isNull(4)) null else row.getText(4), boundedText(row, 5, 65_536), row.getLong(6))
    }
}

internal fun boundedText(row: SQLiteStatement, index: Int, maximum: Int): String {
    val text = row.getText(index)
    if (text.length > maximum) throw SpatialCorruptData()
    return text
}
