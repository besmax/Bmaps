/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import androidx.sqlite.driver.bundled.*
import kotlinx.io.files.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

internal object SqliteSnapshotScenario {
    suspend fun includesCommittedWal() {
        val root = Path(SystemTemporaryDirectory, "bmaps-snapshot-${Uuid.random()}")
        SystemFileSystem.createDirectories(root)
        val source = Path(root, "source.db")
        val snapshot = Path(root, "snapshot.db")
        try {
            BundledSQLiteDriver().open(source.toString()).use { db ->
                fun sql(value: String) { db.prepare(value).use { it.step() } }
                sql("PRAGMA journal_mode=WAL")
                sql("PRAGMA wal_autocheckpoint=0")
                sql("CREATE TABLE edits(value TEXT)")
                sql("INSERT INTO edits VALUES ('committed')")
                assertTrue(SystemFileSystem.exists(Path("$source-wal")))
                SqliteSnapshot.create(source.toString(), snapshot.toString())
                sql("INSERT INTO edits VALUES ('later')")
                BundledSQLiteDriver().open(snapshot.toString(), SQLITE_OPEN_READONLY).use { copy ->
                    copy.prepare("SELECT value FROM edits").use {
                        assertTrue(it.step()); assertEquals("committed", it.getText(0)); assertEquals(false, it.step())
                    }
                }
            }
        } finally {
            SystemFileSystem.list(root).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(root)
        }
    }
}
