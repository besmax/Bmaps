/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor

import bes.max.bmaps.core.datastore.*
import bes.max.bmaps.domain.providers.OPENTOPOGRAPHY_CREDENTIAL
import bes.max.bmaps.domain.providers.OpenTopographyEndpoints
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderCredentialsViewModelTest {
    @Test fun validatesSavesRemovesAndReportsStorageFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var writes = 0
            var removed = false
            var result = CredentialWriteResult.UNAVAILABLE
            val repository = object : ProviderCredentials {
                override suspend fun read(identifier: String) = CredentialResult.Missing
                override suspend fun write(identifier: String, value: String): CredentialWriteResult { writes++; return result }
                override suspend fun remove(identifier: String): CredentialWriteResult { removed = true; return result }
            }
            val model = ProviderCredentialsViewModel(repository)
            model.load(OPENTOPOGRAPHY_CREDENTIAL)
            runCurrent()
            assertEquals(OpenTopographyEndpoints.account, model.state.value.keyRequestUrl)
            model.edit(" ")
            model.save("test")
            runCurrent()
            assertEquals(0, writes)
            assertNotNull(model.state.value.error)
            model.edit("synthetic-key")
            assertFalse(model.state.value.toString().contains("synthetic"))
            model.save("test")
            runCurrent()
            assertNotNull(model.state.value.error)
            result = CredentialWriteResult.SUCCESS
            val event = async { model.events.first() }
            model.save("test")
            runCurrent()
            event.await()
            assertEquals("", model.state.value.draft)
            model.save("test", remove = true)
            runCurrent()
            assertTrue(removed)
        } finally { Dispatchers.resetMain() }
    }
}
