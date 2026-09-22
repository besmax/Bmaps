package bes.max.bmaps.domain.mapbuilder

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import bes.max.bmaps.core.database.PackageDatabase
import bes.max.bmaps.core.database.packageDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackageStorageDeviceTest {
    private val scenarios = PackageStorageScenarios { path ->
        packageDatabase(Room.databaseBuilder<PackageDatabase>(InstrumentationRegistry.getInstrumentation().targetContext, path))
    }

    @Test fun eachLayerHasItsOwnLimitAndElevationIsExcluded() = runBlocking { scenarios.eachLayerHasItsOwnLimitAndElevationIsExcluded() }
    @Test fun elevationIsRequiredAndSurvivesRestart() = runBlocking { scenarios.elevationIsRequiredAndSurvivesRestart() }
    @Test fun annotationsSurviveRestartAndStayIsolated() = runBlocking { scenarios.annotationsSurviveRestartAndStayIsolated() }
    @Test fun layerConfigurationSurvivesReopening() = runBlocking { scenarios.layerConfigurationSurvivesReopening() }
    @Test fun recoverAndReopen() = runBlocking { scenarios.recoverAndReopen() }
    @Test fun reconcilePromotionAndMissingAssets() = runBlocking { scenarios.reconcilePromotionAndMissingAssets() }
    @Test fun paginationAndWriteRollback() = runBlocking { scenarios.paginationAndWriteRollback() }
}
