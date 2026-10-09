/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.feature.constructor.download.ui
import androidx.compose.runtime.*
import platform.Foundation.NSOperationQueue
import platform.UserNotifications.*

@Composable
internal actual fun rememberDownloadNotificationPermission(): (() -> Unit) -> Unit {
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    return { action ->
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
            NSOperationQueue.mainQueue.addOperationWithBlock { if (active) action() }
        }
    }
}
