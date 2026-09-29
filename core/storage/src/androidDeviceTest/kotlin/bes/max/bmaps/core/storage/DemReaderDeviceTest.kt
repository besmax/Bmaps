package bes.max.bmaps.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemReaderDeviceTest {
    @Test fun readsReferenceFilesThroughJni() = runBlocking {
        DemNativeScenarios.sampleReferenceFiles()
    }
}
