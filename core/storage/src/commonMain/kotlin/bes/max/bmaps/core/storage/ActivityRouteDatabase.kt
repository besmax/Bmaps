/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class StoredActivityRoute(val id: String, val revision: Long, val name: String, val vertexCount: Int, val segmentCount: Int, val payload: String)

class ActivityRouteDatabase internal constructor(private val sql: SpatialSql) {
    fun get(id: String): StoredActivityRoute? = sql.query(
        "SELECT id, revision, name, vertex_count, segment_count, substr(payload,1,64000001) FROM routes WHERE id=?", arrayOf<Any?>(id),
    ) { StoredActivityRoute(it.getText(0), it.getLong(1), it.getText(2), it.getLong(3).toInt(), it.getLong(4).toInt(), boundedText(it, 5, 64_000_000)) }.singleOrNull()

    fun page(afterId: String, limit: Int): List<StoredActivityRoute> {
        require(limit in 1..500)
        return sql.query("SELECT id, revision, name, vertex_count, segment_count FROM routes WHERE id>? ORDER BY id LIMIT ?", arrayOf<Any?>(afterId, limit)) {
            StoredActivityRoute(it.getText(0), it.getLong(1), it.getText(2), it.getLong(3).toInt(), it.getLong(4).toInt(), "")
        }
    }

    fun derived(activityId: String): String? = sql.query("SELECT route_id FROM route_derivations WHERE activity_id=?", arrayOf<Any?>(activityId)) { it.getText(0) }.singleOrNull()

    suspend fun save(value: StoredActivityRoute, expectedRevision: Long?): StoredActivityRoute = sql.transaction {
        put(value, expectedRevision)
        value
    }

    suspend fun importRoutes(values: List<StoredActivityRoute>) = sql.transaction {
        require(values.size in 1..100)
        for (value in values) { currentCoroutineContext().ensureActive(); put(value, null) }
    }

    suspend fun derive(activityId: String, value: StoredActivityRoute): String = sql.transaction {
        val existing = derived(activityId)
        if (existing != null && get(existing) != null) return@transaction existing
        if (existing != null) sql.execute("DELETE FROM route_derivations WHERE activity_id=?", activityId)
        put(value, null)
        sql.execute("INSERT INTO route_derivations(activity_id, route_id) VALUES(?, ?)", activityId, value.id)
        value.id
    }

    suspend fun delete(id: String) = sql.transaction {
        if (get(id) == null) throw SpatialNotFound()
        sql.execute("INSERT OR IGNORE INTO route_tombstones(id) VALUES(?)", id)
        sql.execute("DELETE FROM routes WHERE id=?", id)
    }

    private fun put(value: StoredActivityRoute, expected: Long?) {
        require(value.payload.encodeToByteArray().size <= 64_000_000)
        if (sql.scalar("SELECT count(*) FROM route_tombstones WHERE id=?", value.id) != 0L) throw SpatialNotFound()
        val revision = sql.query("SELECT revision FROM routes WHERE id=?", arrayOf<Any?>(value.id)) { it.getLong(0) }.singleOrNull()
        if (expected == null) {
            if (revision != null || value.revision != 0L) throw SpatialRevisionConflict()
        } else if (revision != expected || expected == Long.MAX_VALUE || value.revision != expected + 1) throw SpatialRevisionConflict()
        sql.execute("INSERT INTO routes(id, revision, name, vertex_count, segment_count, payload) VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision, name=excluded.name, vertex_count=excluded.vertex_count, segment_count=excluded.segment_count, payload=excluded.payload",
            value.id, value.revision, value.name, value.vertexCount, value.segmentCount, value.payload)
    }

    companion object {
        internal val SCHEMA = listOf(
            "CREATE TABLE routes(id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, vertex_count INTEGER NOT NULL, segment_count INTEGER NOT NULL, payload TEXT NOT NULL)",
            "CREATE TABLE route_derivations(activity_id TEXT PRIMARY KEY NOT NULL, route_id TEXT NOT NULL UNIQUE)",
            "CREATE TABLE route_tombstones(id TEXT PRIMARY KEY NOT NULL)",
        )
    }
}
