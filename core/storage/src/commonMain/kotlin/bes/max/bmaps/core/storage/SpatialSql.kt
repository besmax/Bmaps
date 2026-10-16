/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException

class SpatialUnsupportedVersion(val version: Long) : IOException("Unsupported spatial schema")
class SpatialCorruptData : IOException("Invalid spatial data")
class SpatialRevisionConflict : IOException("Spatial revision conflict")
class SpatialNotFound : IOException("Spatial record not found")
class SpatialOwnerBusy : IOException("Another activity owns recording")

internal class SpatialSql(private val connection: SQLiteConnection) {
    fun execute(sql: String, vararg parameters: Any?) {
        connection.prepare(sql).use { it.bind(parameters); it.step() }
    }

    fun scalar(sql: String, vararg parameters: Any?): Long = connection.prepare(sql).use {
        it.bind(parameters)
        if (!it.step()) throw SpatialCorruptData()
        it.getLong(0)
    }

    fun <T> query(sql: String, parameters: Array<out Any?> = emptyArray(), read: (SQLiteStatement) -> T): List<T> =
        connection.prepare(sql).use {
            it.bind(parameters)
            buildList { while (it.step()) add(read(it)) }
        }

    suspend fun <T> transaction(block: suspend () -> T): T {
        execute("BEGIN IMMEDIATE")
        try {
            val result = block()
            currentCoroutineContext().ensureActive()
            execute("COMMIT")
            return result
        } catch (error: Throwable) {
            try { execute("ROLLBACK") } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            throw error
        }
    }

    fun close() = connection.close()

    companion object {
        suspend fun open(path: String, verify: Boolean, schema: List<String>): SpatialSql {
            val sql = SpatialSql(BundledSQLiteDriver().open(path,
                SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW))
            try {
                sql.execute("PRAGMA busy_timeout=5000")
                sql.execute("PRAGMA trusted_schema=OFF")
                sql.execute("PRAGMA foreign_keys=ON")
                sql.execute("PRAGMA journal_mode=DELETE")
                sql.execute("PRAGMA synchronous=FULL")
                sql.execute("PRAGMA secure_delete=ON")
                val version = sql.scalar("PRAGMA user_version")
                if (version == 0L) {
                    if (sql.scalar("SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'") != 0L) throw SpatialCorruptData()
                    sql.transaction { schema.forEach { sql.execute(it) }; sql.execute("PRAGMA user_version=1") }
                } else if (version != 1L) throw SpatialUnsupportedVersion(version)
                if (verify) {
                    if (sql.query("PRAGMA quick_check") { it.getText(0) } != listOf("ok") ||
                        sql.query("PRAGMA foreign_key_check") { it.getText(0) }.isNotEmpty()) throw SpatialCorruptData()
                }
                return sql
            } catch (error: Throwable) { sql.close(); throw error }
        }
    }
}

private fun SQLiteStatement.bind(values: Array<out Any?>) {
    values.forEachIndexed { index, value ->
        when (value) {
            null -> bindNull(index + 1)
            is String -> bindText(index + 1, value)
            is Long -> bindLong(index + 1, value)
            is Int -> bindLong(index + 1, value.toLong())
            else -> error("Unsupported SQL parameter")
        }
    }
}
