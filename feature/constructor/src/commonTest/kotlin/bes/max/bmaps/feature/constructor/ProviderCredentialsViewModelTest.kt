package bes.max.bmaps.feature.constructor

import bes.max.bmaps.core.datastore.*
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
