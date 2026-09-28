package bes.max.bmaps.core.storage

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class DemReaderIosTest {
    @Test fun readsReferenceFilesThroughCinterop() = runBlocking {
        DemNativeScenarios.sampleReferenceFiles()
    }
}
