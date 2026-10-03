/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import bmaps.feature.viewer.generated.resources.*
import bes.max.bmaps.core.location.LocationStatus
import org.jetbrains.compose.resources.StringResource

internal fun locationMessage(status: LocationStatus): StringResource = when (status) {
    LocationStatus.WAITING, LocationStatus.ACTIVE -> Res.string.location_waiting
    LocationStatus.STALE -> Res.string.location_stale
    LocationStatus.PERMISSION_DENIED -> Res.string.location_permission_denied
    LocationStatus.PRECISE_PERMISSION_REQUIRED -> Res.string.location_precise_permission
    LocationStatus.DISABLED -> Res.string.location_disabled
    LocationStatus.UNAVAILABLE -> Res.string.location_unavailable
    LocationStatus.TIMED_OUT -> Res.string.location_timeout
    LocationStatus.INVALID_FIX -> Res.string.location_invalid_fix
}
