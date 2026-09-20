package bes.max.bmaps.core.datastore

import kotlinx.coroutines.flow.Flow

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val defaultCoordinateSystem: String = "EPSG:4326",
    val clusterMapObjects: Boolean = true,
)

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setDisplayPreferences(theme: ThemePreference, clusterMapObjects: Boolean)
    suspend fun setDefaultCoordinateSystem(identifier: String)
}
