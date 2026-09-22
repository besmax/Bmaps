package bes.max.bmaps.domain.mapbuilder

import androidx.room.Room
import bes.max.bmaps.core.database.PackageDatabase
import bes.max.bmaps.core.database.packageDatabase
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class PackageStorageIosTest {
    private val scenarios = PackageStorageScenarios { path ->
        packageDatabase(Room.databaseBuilder<PackageDatabase>(name = path))
    }

    @Test fun eachLayerHasItsOwnLimitAndElevationIsExcluded() = runBlocking { scenarios.eachLayerHasItsOwnLimitAndElevationIsExcluded() }
    @Test fun elevationIsRequiredAndSurvivesRestart() = runBlocking { scenarios.elevationIsRequiredAndSurvivesRestart() }
    @Test fun annotationsSurviveRestartAndStayIsolated() = runBlocking { scenarios.annotationsSurviveRestartAndStayIsolated() }
    @Test fun layerConfigurationSurvivesReopening() = runBlocking { scenarios.layerConfigurationSurvivesReopening() }
    @Test fun recoverAndReopen() = runBlocking { scenarios.recoverAndReopen() }
    @Test fun reconcilePromotionAndMissingAssets() = runBlocking { scenarios.reconcilePromotionAndMissingAssets() }
    @Test fun paginationAndWriteRollback() = runBlocking { scenarios.paginationAndWriteRollback() }
}
