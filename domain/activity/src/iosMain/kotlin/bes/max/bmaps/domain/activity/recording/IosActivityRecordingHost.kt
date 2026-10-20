/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.domain.activity.recording

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityProfile
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import platform.CoreLocation.*
import platform.Foundation.*
import platform.UIKit.*

@BindingContainer
@ContributesTo(AppScope::class)
object IosActivityRecordingBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun host(): ActivityRecordingHost = IosActivityRecordingHost()
}

private class IosActivityRecordingHost : ActivityRecordingHost {
    override val interruptions: Flow<Unit> = emptyFlow()
    override suspend fun start(profile: ActivityProfile): ActivityFailure? {
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return ActivityFailure.ForegroundRequired
        if (!CLLocationManager.locationServicesEnabled()) return ActivityFailure.LocationDisabled
        val manager = CLLocationManager()
        if (manager.authorizationStatus != kCLAuthorizationStatusAuthorizedWhenInUse && manager.authorizationStatus != kCLAuthorizationStatusAuthorizedAlways)
            return ActivityFailure.PermissionRequired
        if (manager.accuracyAuthorization != CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy) return ActivityFailure.PreciseLocationRequired
        return null
    }
    override fun stop() = Unit
}

internal actual fun activityEpochMillis() = (NSDate().timeIntervalSince1970 * 1_000).toLong()
internal actual fun activityMonotonicMillis() = (NSProcessInfo.processInfo.systemUptime * 1_000).toLong()
internal actual fun activityIdentity(): String = NSUUID().UUIDString
