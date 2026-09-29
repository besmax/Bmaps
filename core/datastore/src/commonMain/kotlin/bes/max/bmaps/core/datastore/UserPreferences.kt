/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.datastore

import kotlinx.coroutines.flow.Flow

enum class ThemePreference { SYSTEM, LIGHT, DARK }

enum class CoordinateFormat { DECIMAL_DEGREES, DEGREES_MINUTES, DEGREES_MINUTES_SECONDS }

enum class DisplayCoordinateSystem(val identifier: String) {
    WGS_84("EPSG:4326"), PULKOVO_1942("EPSG:4284"), PZ_90_11("EPSG:9475");

    companion object {
        fun fromIdentifier(identifier: String): DisplayCoordinateSystem? = entries.firstOrNull { it.identifier == identifier }
    }
}

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
        coordinateFormat: CoordinateFormat, defaultCoordinateSystem: String)
    suspend fun setDefaultCoordinateSystem(identifier: String)
}
