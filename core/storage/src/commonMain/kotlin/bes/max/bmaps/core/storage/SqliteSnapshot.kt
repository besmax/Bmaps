/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import androidx.sqlite.driver.bundled.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

object SqliteSnapshot {
    suspend fun create(source: String, destination: String) = withContext(Dispatchers.IO) {
        BundledSQLiteDriver().open(source, SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW).use { db ->
            db.prepare("PRAGMA trusted_schema=OFF").use { it.step() }
            db.prepare("VACUUM INTO ?").use { it.bindText(1, destination); it.step() }
        }
        syncPath(destination, false)
    }
}
