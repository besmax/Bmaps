package bes.max.bmaps.core.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class AnnotationRecord(val id: String, val geoJson: String, val west: Double, val south: Double, val east: Double, val north: Double)
data class AnnotationBounds(val west: Double, val south: Double, val east: Double, val north: Double)

class AnnotationDatabase private constructor(private val connection: SQLiteConnection) {
    fun query(bounds: AnnotationBounds?, after: String?, limit: Int): List<AnnotationRecord> {
        require(limit in 1..1001)
        val box = bounds ?: AnnotationBounds(-180.0, -90.0, 180.0, 90.0)
        require(listOf(box.west, box.south, box.east, box.north).all { it.isFinite() })
        require(box.west in -180.0..180.0 && box.east in -180.0..180.0 && box.south in -90.0..90.0 && box.north in box.south..90.0)
        val longitude = if (box.west <= box.east) "(west <= ? AND east >= ?)" else "(west <= ? OR east >= ?)"
        return connection.prepare("SELECT id, length(feature), feature, west, south, east, north FROM annotations WHERE id > ? AND south <= ? AND north >= ? AND $longitude ORDER BY id LIMIT ?").use {
            it.bindText(1, after ?: ""); it.bindDouble(2, box.north); it.bindDouble(3, box.south)
            it.bindDouble(4, box.east); it.bindDouble(5, box.west); it.bindLong(6, limit.toLong())
            buildList {
                while (it.step()) {
                    check(it.getLong(1) in 1..256_000)
                    add(AnnotationRecord(it.getText(0), it.getText(2), it.getDouble(3), it.getDouble(4), it.getDouble(5), it.getDouble(6)))
                }
            }
        }
    }

    suspend fun change(values: List<AnnotationRecord>, deleteId: String?, maxBytes: Long, updatedAt: Long) {
        require(values.size <= 1000)
        transaction {
            for (value in values) {
                currentCoroutineContext().ensureActive()
                require(value.id.length in 1..128 && value.geoJson.encodeToByteArray().size <= 256_000)
                connection.prepare("INSERT OR REPLACE INTO annotations(id, feature, west, south, east, north) VALUES (?, ?, ?, ?, ?, ?)").use {
                    it.bindText(1, value.id); it.bindText(2, value.geoJson)
                    it.bindDouble(3, value.west); it.bindDouble(4, value.south); it.bindDouble(5, value.east); it.bindDouble(6, value.north); it.step()
                }
            }
            if (deleteId != null) connection.prepare("DELETE FROM annotations WHERE id=?").use { it.bindText(1, deleteId); it.step() }
            connection.prepare("UPDATE annotation_owner SET updated_at=?").use { it.bindLong(1, updatedAt); it.step() }
            val bytes = scalar("PRAGMA page_count") * scalar("PRAGMA page_size")
            if (bytes > maxBytes) throw StorageLimitExceeded(maxBytes, bytes)
        }
    }

    fun modifiedAt(): Long = scalar("SELECT updated_at FROM annotation_owner")

    private fun execute(sql: String) { connection.prepare(sql).use { it.step() } }
    private fun scalar(sql: String): Long = connection.prepare(sql).use { check(it.step()); it.getLong(0) }
    private suspend fun transaction(block: suspend () -> Unit) {
        execute("BEGIN IMMEDIATE")
        try { block(); currentCoroutineContext().ensureActive(); execute("COMMIT") }
        catch (failure: Throwable) {
            try { execute("ROLLBACK") } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    companion object {
        suspend fun <T> access(path: String, packageId: String, create: Boolean = false, verify: Boolean = false, block: suspend AnnotationDatabase.() -> T): T = withContext(Dispatchers.IO) {
            val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW or (if (create) SQLITE_OPEN_CREATE else 0)
            val connection = BundledSQLiteDriver().open(path, flags)
            try {
                val database = AnnotationDatabase(connection)
                database.execute("PRAGMA busy_timeout=5000")
                database.execute("PRAGMA journal_mode=DELETE")
                database.execute("PRAGMA synchronous=FULL")
                val version = database.scalar("PRAGMA user_version")
                if (version == 0L && create) database.transaction {
                    database.execute("CREATE TABLE annotation_owner (package_id TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                    connection.prepare("INSERT INTO annotation_owner VALUES (?, 0)").use { it.bindText(1, packageId); it.step() }
                    database.execute("CREATE TABLE annotations (id TEXT PRIMARY KEY NOT NULL, feature TEXT NOT NULL, west REAL NOT NULL, south REAL NOT NULL, east REAL NOT NULL, north REAL NOT NULL)")
                    database.execute("CREATE INDEX annotation_bounds ON annotations(south, north, west, east)")
                    database.execute("PRAGMA user_version=1")
                } else check(version == 1L) { "Unsupported annotation schema" }
                connection.prepare("SELECT package_id FROM annotation_owner").use { check(it.step() && it.getText(0) == packageId && !it.step()) }
                if (verify) connection.prepare("PRAGMA quick_check").use { check(it.step() && it.getText(0) == "ok" && !it.step()) }
                database.block()
            } finally { connection.close() }
        }
    }
}
