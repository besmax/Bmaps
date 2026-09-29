package bes.max.bmaps.core.datastore

import kotlinx.coroutines.flow.Flow

enum class ThemePreference { SYSTEM, LIGHT, DARK }

enum class CoordinateFormat { DECIMAL_DEGREES, DEGREES_MINUTES, DEGREES_MINUTES_SECONDS }

data class UserPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val defaultCoordinateSystem: String = "EPSG:4326",
    val clusterMapObjects: Boolean = true,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES,
)

interface UserPreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setDisplayPreferences(theme: ThemePreference, clusterMapObjects: Boolean,
        coordinateFormat: CoordinateFormat = CoordinateFormat.DECIMAL_DEGREES)
    suspend fun setDefaultCoordinateSystem(identifier: String)
}
