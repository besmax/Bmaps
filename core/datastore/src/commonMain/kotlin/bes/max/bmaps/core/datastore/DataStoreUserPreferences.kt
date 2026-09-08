package bes.max.bmaps.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DataStoreUserPreferences(private val dataStore: DataStore<Preferences>) : UserPreferencesRepository {
    override val preferences: Flow<UserPreferences> = dataStore.data.map { stored ->
        UserPreferences(
            theme = ThemePreference.entries.firstOrNull { it.name == stored[ThemeKey] } ?: ThemePreference.SYSTEM,
            defaultCoordinateSystem = stored[CoordinateSystemKey] ?: "EPSG:4326",
        )
    }.distinctUntilChanged()

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[ThemeKey] = theme.name }
    }

    override suspend fun setDefaultCoordinateSystem(identifier: String) {
        dataStore.edit { it[CoordinateSystemKey] = identifier }
    }

    private companion object {
        val ThemeKey = stringPreferencesKey("theme")
        val CoordinateSystemKey = stringPreferencesKey("default_coordinate_system")
    }
}

internal const val PREFERENCES_NAME = "bmaps"
