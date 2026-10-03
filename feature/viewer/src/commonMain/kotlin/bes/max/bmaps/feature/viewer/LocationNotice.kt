/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import bmaps.feature.viewer.generated.resources.*
import bes.max.bmaps.core.location.*
import bes.max.bmaps.core.ui.components.MapStatusNotice
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LocationNotice(
    state: LocationState,
    access: LocationAccess,
    model: LocationViewModel,
    modifier: Modifier = Modifier,
    outsideCoverage: Boolean = false,
) {
    if (state.status == LocationStatus.ACTIVE && !outsideCoverage) return
    val settings = state.status in setOf(LocationStatus.PERMISSION_DENIED,
        LocationStatus.PRECISE_PERMISSION_REQUIRED, LocationStatus.DISABLED)
    MapStatusNotice(
        message = stringResource(if (outsideCoverage) Res.string.location_outside_map else locationMessage(state.status)),
        busy = state.status == LocationStatus.WAITING,
        modifier = modifier,
        actionLabel = if (outsideCoverage) null else stringResource(if (settings) Res.string.location_settings else Res.string.location_retry),
        onAction = {
            when (state.status) {
                LocationStatus.PERMISSION_DENIED, LocationStatus.PRECISE_PERMISSION_REQUIRED -> access.requestPermission(openSettingsIfDenied = true)
                LocationStatus.DISABLED -> access.openSettings()
                else -> model.refresh()
            }
        },
    )
}
