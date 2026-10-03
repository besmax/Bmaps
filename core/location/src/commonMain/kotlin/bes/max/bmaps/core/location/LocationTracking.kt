/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun LocationTracking(
    model: LocationViewModel,
    enabled: Boolean = true,
    onStopped: () -> Unit = {},
): LocationAccess {
    val stopped by rememberUpdatedState(onStopped)
    val access = rememberLocationAccess(model::refresh)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(access, enabled) {
        if (enabled) access.requestPermission()
    }
    LaunchedEffect(model, lifecycle, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { model.track() } finally { stopped() }
        }
    }
    return access
}
