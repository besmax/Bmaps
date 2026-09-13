@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.feature.constructor

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
