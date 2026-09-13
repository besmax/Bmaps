package bes.max.bmaps.core.mbtiles

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TileAddress(val zoom: Int, val column: Long, val row: Long)
data class TileWrite(val address: TileAddress, val bytes: ByteArray)
data class MissingTile(val address: TileAddress, val reason: String)
data class TileCounts(val downloaded: Long, val failed: Long)
class ClosedMbTiles : IllegalStateException("MBTiles handle is closed")
class MbTilesSizeExceeded : IllegalStateException("MBTiles size limit exceeded")

class MbTiles private constructor(private val connection: SQLiteConnection, private val writable: Boolean) {
    private val mutex = Mutex()
    private var closed = false

    suspend fun read(address: TileAddress): ByteArray? = access {
        statement("SELECT length(tile_data), tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", address) {
            if (step()) { check(getLong(0) in 1..MAX_TILE_BYTES.toLong()); getBlob(1) } else null
        }
    }

    suspend fun contains(address: TileAddress): Boolean = access {
        statement("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", address) { step() }
    }

    suspend fun metadata(): Map<String, String> = access {
        connection.prepare("SELECT name, value FROM metadata").use { query ->
            buildMap {
                while (query.step()) {
                    check(size < 256)
                    val name = query.getText(0)
                    val value = query.getText(1)
                    check(name.length <= 128 && value.length <= 8192)
                    put(name, value)
                }
            }
        }
    }

    suspend fun counts(): TileCounts = access {
        TileCounts(scalar("SELECT count(*) FROM tiles"), scalar("SELECT count(*) FROM bmaps_missing_tiles"))
    }

    suspend fun missing(after: TileAddress? = null, limit: Int = 256): List<MissingTile> = access {
        require(limit in 1..1024)
        connection.prepare("""
            SELECT zoom_level, tile_column, xyz_row, reason FROM bmaps_missing_tiles
            WHERE (zoom_level, tile_column, xyz_row) > (?, ?, ?)
            ORDER BY zoom_level, tile_column, xyz_row LIMIT ?
        """.trimIndent()).use { query ->
            query.bindLong(1, after?.zoom?.toLong() ?: -1)
            query.bindLong(2, after?.column ?: -1)
            query.bindLong(3, after?.row ?: -1)
            query.bindLong(4, limit.toLong())
            buildList {
                while (query.step()) add(MissingTile(
                    TileAddress(query.getLong(0).toInt(), query.getLong(1), query.getLong(2)), query.getText(3),
                ))
            }
        }
    }

    suspend fun write(tiles: List<TileWrite>, failures: List<MissingTile>, maxDatabaseBytes: Long) = access {
        check(writable)
        require(tiles.size + failures.size in 1..32)
        require(tiles.sumOf { it.bytes.size.toLong() } <= MAX_BATCH_BYTES)
        require(maxDatabaseBytes >= 0)
        tiles.forEach { require(it.bytes.size in 1..MAX_TILE_BYTES); tmsRow(it.address) }
        failures.forEach { tmsRow(it.address); require(it.reason.length in 1..64) }
        transaction {
            tiles.forEach { tile ->
                currentCoroutineContext().ensureActive()
                statement("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", tile.address) {
                    bindBlob(4, tile.bytes)
                    step()
                }
                deleteFailure(tile.address)
            }
            failures.forEach { failure ->
                val present = statement(
                    "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", failure.address,
                ) { step() }
                if (!present) connection.prepare("INSERT OR REPLACE INTO bmaps_missing_tiles VALUES (?, ?, ?, ?)").use {
                    it.bindLong(1, failure.address.zoom.toLong())
                    it.bindLong(2, failure.address.column)
                    it.bindLong(3, failure.address.row)
                    it.bindText(4, failure.reason)
                    it.step()
                }
            }
            if (scalar("PRAGMA page_count") * scalar("PRAGMA page_size") > maxDatabaseBytes) {
                throw MbTilesSizeExceeded()
            }
        }
    }

    suspend fun verify(accepts: (TileAddress) -> Boolean = { true }) = access {
        connection.prepare("PRAGMA quick_check").use {
            check(it.step() && it.getText(0) == "ok" && !it.step()) { "Invalid MBTiles database" }
        }
        connection.prepare("SELECT zoom_level, tile_column, tile_row, length(tile_data) FROM tiles").use {
            while (it.step()) {
                currentCoroutineContext().ensureActive()
                val zoom = it.getLong(0)
                check(zoom in 0..63)
                val stored = TileAddress(zoom.toInt(), it.getLong(1), it.getLong(2))
                check(accepts(stored.copy(row = tmsRow(stored))))
                check(it.getLong(3) in 1..MAX_TILE_BYTES.toLong())
            }
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                closed = true
                connection.close()
            }
        }
    }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) throw ClosedMbTiles()
            block()
        }
    }

    private suspend fun transaction(block: suspend () -> Unit) {
        execute("BEGIN IMMEDIATE")
        try {
            block()
            currentCoroutineContext().ensureActive()
            execute("COMMIT")
        } catch (failure: Throwable) {
            try { execute("ROLLBACK") } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun deleteFailure(address: TileAddress) {
        connection.prepare("DELETE FROM bmaps_missing_tiles WHERE zoom_level=? AND tile_column=? AND xyz_row=?").use {
            it.bindLong(1, address.zoom.toLong())
            it.bindLong(2, address.column)
            it.bindLong(3, address.row)
            it.step()
        }
    }

    private inline fun <T> statement(sql: String, address: TileAddress, block: SQLiteStatement.() -> T): T {
        val row = tmsRow(address)
        return connection.prepare(sql).use {
            it.bindLong(1, address.zoom.toLong())
            it.bindLong(2, address.column)
            it.bindLong(3, row)
            it.block()
        }
    }

    private fun scalar(sql: String): Long = connection.prepare(sql).use { check(it.step()); it.getLong(0) }
    private fun execute(sql: String) { connection.prepare(sql).use { it.step() } }

    companion object {
        const val MAX_TILE_BYTES = 2_000_000
        const val MAX_BATCH_BYTES = 4_000_000L

        suspend fun create(path: String, metadata: Map<String, String>): MbTiles = open(path, true, true, metadata)
        suspend fun open(path: String, writable: Boolean = false): MbTiles = open(path, writable, false, emptyMap())

        private suspend fun open(path: String, writable: Boolean, create: Boolean, metadata: Map<String, String>): MbTiles {
            var pending: MbTiles? = null
            try {
                return withContext(Dispatchers.IO) {
                    val flags = (if (writable) SQLITE_OPEN_READWRITE else SQLITE_OPEN_READONLY) or
                        (if (create) SQLITE_OPEN_CREATE else 0) or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW
                    val connection = BundledSQLiteDriver().open(path, flags)
                    val handle = MbTiles(connection, writable)
                    pending = handle
                    handle.execute("PRAGMA busy_timeout=5000")
                    if (writable) {
                        handle.execute("PRAGMA journal_mode=DELETE")
                        handle.execute("PRAGMA synchronous=FULL")
                    }
                    if (create) handle.transaction {
                        handle.execute("CREATE TABLE metadata (name TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                        handle.execute("""
                            CREATE TABLE tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                            tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL,
                            PRIMARY KEY (zoom_level, tile_column, tile_row))
                        """.trimIndent())
                        handle.execute("""
                            CREATE TABLE bmaps_missing_tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL,
                            xyz_row INTEGER NOT NULL, reason TEXT NOT NULL,
                            PRIMARY KEY (zoom_level, tile_column, xyz_row))
                        """.trimIndent())
                        metadata.forEach { (name, value) ->
                            connection.prepare("INSERT INTO metadata VALUES (?, ?)").use {
                                it.bindText(1, name); it.bindText(2, value); it.step()
                            }
                        }
                    }
                    handle
                }
            } catch (failure: Throwable) {
                try { pending?.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        }

        fun tmsRow(address: TileAddress): Long {
            require(address.zoom in 0..63)
            val max = if (address.zoom == 63) Long.MAX_VALUE else (1L shl address.zoom) - 1
            require(address.column in 0..max && address.row in 0..max)
            return max - address.row
        }
    }
}
