package bes.max.bmaps.feature.constructor

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberDownloadNotificationPermission(): (() -> Unit) -> Unit
