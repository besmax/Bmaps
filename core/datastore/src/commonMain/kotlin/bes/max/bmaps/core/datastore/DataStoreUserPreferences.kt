/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
            clusterMapObjects = stored[ClusterMapObjectsKey] ?: true,
            coordinateFormat = CoordinateFormat.entries.firstOrNull { it.name == stored[CoordinateFormatKey] }
                ?: CoordinateFormat.DECIMAL_DEGREES,
        )
    }.distinctUntilChanged()

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[ThemeKey] = theme.name }
    }

    override suspend fun setDefaultCoordinateSystem(identifier: String) {
        dataStore.edit { it[CoordinateSystemKey] = identifier }
    }

    override suspend fun setDisplayPreferences(theme: ThemePreference, clusterMapObjects: Boolean,
        coordinateFormat: CoordinateFormat, defaultCoordinateSystem: String) {
        dataStore.edit {
            it[ThemeKey] = theme.name
            it[ClusterMapObjectsKey] = clusterMapObjects
            it[CoordinateFormatKey] = coordinateFormat.name
            it[CoordinateSystemKey] = defaultCoordinateSystem
        }
    }

    private companion object {
        val CoordinateFormatKey = stringPreferencesKey("coordinate_format")
        val ThemeKey = stringPreferencesKey("theme")
        val CoordinateSystemKey = stringPreferencesKey("default_coordinate_system")
        val ClusterMapObjectsKey = booleanPreferencesKey("cluster_map_objects")
    }
}

internal const val PREFERENCES_NAME = "bmaps"
