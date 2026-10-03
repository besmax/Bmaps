/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timestampMillis: Long,
    val speedMetersPerSecond: Double? = null,
    val courseDegrees: Double? = null,
)

data class LocationRequest(val intervalMillis: Long = 1_000, val minimumDistanceMeters: Double = 0.0) {
    init {
        require(intervalMillis > 0 && minimumDistanceMeters.isFinite() && minimumDistanceMeters >= 0)
    }
}

enum class LocationStatus { WAITING, ACTIVE, STALE, PERMISSION_DENIED, PRECISE_PERMISSION_REQUIRED, DISABLED, UNAVAILABLE, TIMED_OUT, INVALID_FIX }
data class LocationState(val status: LocationStatus = LocationStatus.WAITING, val fix: LocationFix? = null)

interface LocationService {
    fun observe(request: LocationRequest = LocationRequest()): Flow<LocationState>
}

interface LocationAccess {
    fun requestPermission(openSettingsIfDenied: Boolean = false)
    fun openSettings()
}

@Composable
expect fun rememberLocationAccess(onChanged: () -> Unit): LocationAccess

internal expect fun locationTimeMillis(): Long
internal expect fun locationDiagnostic(message: String, error: Throwable? = null)
