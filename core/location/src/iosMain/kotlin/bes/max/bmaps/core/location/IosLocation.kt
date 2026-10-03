/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package bes.max.bmaps.core.location

import androidx.compose.runtime.*
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreLocation.*
import platform.Foundation.NSLog
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

@BindingContainer
@ContributesTo(AppScope::class)
object IosLocationBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun service(): LocationService = IosLocationService()
}

private class IosLocationService : LocationService {
    override fun observe(request: LocationRequest): Flow<LocationState> = callbackFlow {
        val session = IosLocationSession(request) { trySend(it); Unit }
        session.start()
        awaitClose { session.stop() }
    }
}

private class IosLocationSession(
    request: LocationRequest,
    private val onState: (LocationState) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager().apply {
        desiredAccuracy = kCLLocationAccuracyBest
        distanceFilter = request.minimumDistanceMeters
    }

    fun start() {
        manager.delegate = this
        updateAuthorization()
    }

    fun stop() {
        manager.stopUpdatingLocation()
        manager.delegate = null
    }

    private fun updateAuthorization() {
        when {
            !CLLocationManager.locationServicesEnabled() -> {
                manager.stopUpdatingLocation()
                onState(LocationState(LocationStatus.DISABLED))
            }
            manager.authorizationStatus == kCLAuthorizationStatusDenied || manager.authorizationStatus == kCLAuthorizationStatusRestricted -> {
                manager.stopUpdatingLocation()
                onState(LocationState(LocationStatus.PERMISSION_DENIED))
            }
            manager.authorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse || manager.authorizationStatus == kCLAuthorizationStatusAuthorizedAlways -> {
                onState(LocationState())
                manager.startUpdatingLocation()
            }
            else -> onState(LocationState(LocationStatus.PERMISSION_DENIED))
        }
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) { updateAuthorization() }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        if (location.horizontalAccuracy < 0) return
        val coordinate = location.coordinate.useContents { latitude to longitude }
        onState(LocationState(LocationStatus.ACTIVE, LocationFix(
            coordinate.first, coordinate.second, location.horizontalAccuracy,
            (location.timestamp.timeIntervalSince1970 * 1_000).toLong(),
            location.speed.takeIf { it >= 0 }, location.course.takeIf { it >= 0 },
        )))
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        when (didFailWithError.code) {
            kCLErrorLocationUnknown.toLong() -> onState(LocationState())
            kCLErrorDenied.toLong() -> updateAuthorization()
            else -> onState(LocationState(LocationStatus.UNAVAILABLE))
        }
    }
}

internal actual fun locationTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000).toLong()

internal actual fun locationDiagnostic(message: String, error: Throwable?) {
    val text = if (error == null) message else "$message: $error"
    memScoped {
        NSLog("%s", "[BmapsLocation] $text".cstr.ptr)
    }
}

@Composable
actual fun rememberLocationAccess(onChanged: () -> Unit): LocationAccess {
    val changed by rememberUpdatedState(onChanged)
    val manager = remember { CLLocationManager() }
    val delegate = remember {
        object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) { changed() }
        }
    }
    DisposableEffect(manager, delegate) {
        manager.delegate = delegate
        onDispose { manager.delegate = null }
    }
    return remember(manager, delegate) {
        object : LocationAccess {
            override fun requestPermission(openSettingsIfDenied: Boolean) {
                when (manager.authorizationStatus) {
                    kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
                    kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> if (openSettingsIfDenied) openSettings()
                    else -> changed()
                }
            }
            override fun openSettings() {
                NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                    UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null)
                }
            }
        }
    }
}
