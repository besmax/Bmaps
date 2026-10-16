/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PackageUseGuardTest {
    @Test fun recordingProtectsItsMapUntilReleased() = runTest {
        val guard = PackageUseGuard()
        assertTrue(guard.retain("map"))
        assertFalse(guard.retain("another"))
        assertNull(guard.deleting("map") { true })
        assertEquals(true, guard.deleting("another") { true })
        guard.release()
        assertEquals(true, guard.deleting("map") { true })
    }
    @Test fun startWaitsForAnAlreadyRunningDeletion() = runTest {
        val guard = PackageUseGuard()
        val finish = CompletableDeferred<Unit>()
        val deleting = async { guard.deleting("map") { finish.await(); true } }
        runCurrent()
        val starting = async { guard.retain("map") }
        runCurrent()
        assertFalse(starting.isCompleted)
        finish.complete(Unit)
        assertEquals(true, deleting.await())
        assertTrue(starting.await())
    }
}
