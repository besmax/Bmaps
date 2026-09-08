package bes.max.bmaps.core.datastore

import kotlinx.coroutines.flow.Flow

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val defaultCoordinateSystem: String = "EPSG:4326",
)

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setDefaultCoordinateSystem(identifier: String)
}
