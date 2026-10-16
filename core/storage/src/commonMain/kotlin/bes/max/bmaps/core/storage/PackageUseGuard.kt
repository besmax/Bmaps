/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@SingleIn(AppScope::class)
class PackageUseGuard {
    private val mutex = Mutex()
    private var active: String? = null
    suspend fun retain(id: String): Boolean = mutex.withLock {
        if (active != null && active != id) false else { active = id; true }
    }
    suspend fun release() = mutex.withLock { active = null }
    suspend fun <T : Any> deleting(id: String, block: suspend () -> T): T? = mutex.withLock {
        if (active == id) null else block()
    }
}
