package bes.max.bmaps.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DataStoreUserPreferencesTest {
    @Test
    fun preferencesSurviveClosingAndReopeningTheFile() = runTest {
        val directory = Files.createTempDirectory("bmaps-preferences").toFile()
        val file = directory.resolve("test.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreUserPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(UserPreferences(), repository.preferences.first())
            repository.setTheme(ThemePreference.DARK)
            repository.setDefaultCoordinateSystem("EPSG:3857")
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = DataStoreUserPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(UserPreferences(ThemePreference.DARK, "EPSG:3857"), reopened.preferences.first())
            reopened.setTheme(ThemePreference.LIGHT)
            assertEquals("EPSG:3857", reopened.preferences.first().defaultCoordinateSystem)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownThemeFallsBackWithoutDiscardingOtherPreferences() = runTest {
        val directory = Files.createTempDirectory("bmaps-preferences").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("test.preferences_pb") }
            store.edit {
                it[stringPreferencesKey("theme")] = "FUTURE_THEME"
                it[stringPreferencesKey("default_coordinate_system")] = "local:custom"
            }
            assertEquals(
                UserPreferences(ThemePreference.SYSTEM, "local:custom"),
                DataStoreUserPreferences(store).preferences.first(),
            )
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            directory.deleteRecursively()
        }
    }
}
