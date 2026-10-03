/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@BindingContainer
@ContributesTo(AppScope::class)
object AndroidLocationBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun service(context: Context): LocationService = AndroidLocationService(context)
}

private fun Context.preciseLocationGranted() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private class AndroidLocationService(private val context: Context) : LocationService {
    override fun observe(request: LocationRequest): Flow<LocationState> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val fine = context.preciseLocationGranted()
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationDiagnostic("Subscription started: precise=$fine, approximate=$coarse, enabled=${manager?.isLocationEnabled}")
        if (!fine) {
            trySend(LocationState(if (coarse) LocationStatus.PRECISE_PERMISSION_REQUIRED else LocationStatus.PERMISSION_DENIED))
            awaitClose { locationDiagnostic("Subscription stopped: permission missing") }
            return@callbackFlow
        }
        val candidates = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { manager?.allProviders?.contains(it) == true }
        if (manager == null || candidates.isEmpty()) {
            locationDiagnostic("No supported device location provider")
            trySend(LocationState(LocationStatus.UNAVAILABLE))
            awaitClose {}
            return@callbackFlow
        }
        val registered = mutableListOf<String>()
        val currentRequests = mutableListOf<CancellationSignal>()
        var accepted: LocationFix? = null
        var acceptedProvider: String? = null
        var permissionFailure = false

        fun receive(location: Location, delivery: String) {
            val wallTime = locationTimeMillis()
            val age = if (location.elapsedRealtimeNanos > 0) {
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            } else wallTime - location.time
            locationDiagnostic("Fix received: provider=${location.provider}, delivery=$delivery, ageMs=$age, accuracyM=${location.accuracy}")
            if (!location.hasAccuracy() || age !in -5_000L..30_000L) {
                locationDiagnostic("Fix ignored: missing accuracy or expired acquisition time")
                return
            }
            val fix = LocationFix(
                location.latitude, location.longitude, location.accuracy.toDouble(), wallTime - age,
                location.speed.toDouble().takeIf { location.hasSpeed() && it.isFinite() && it >= 0 },
                location.bearing.toDouble().takeIf { location.hasBearing() && it.isFinite() && it >= 0 && it < 360 },
            )
            val previous = accepted
            if (previous != null) {
                val previousAge = wallTime - previous.timestampMillis
                val recentGps = acceptedProvider == LocationManager.GPS_PROVIDER && previousAge < 10_000
                val worseFallback = location.provider != LocationManager.GPS_PROVIDER && fix.accuracyMeters > previous.accuracyMeters
                if (fix.timestampMillis < previous.timestampMillis || recentGps && worseFallback) {
                    locationDiagnostic("Fix ignored: a newer or more accurate position is available")
                    return
                }
            }
            accepted = fix
            acceptedProvider = location.provider
            trySend(LocationState(LocationStatus.ACTIVE, fix))
        }

        fun publishAvailability() {
            val enabled = candidates.filter { manager.isProviderEnabled(it) }
            val fix = accepted?.takeIf { locationTimeMillis() - it.timestampMillis in -5_000L..30_000L }
            locationDiagnostic("Available providers: $enabled")
            trySend(when {
                !manager.isLocationEnabled || enabled.isEmpty() -> LocationState(LocationStatus.DISABLED)
                fix != null -> LocationState(LocationStatus.ACTIVE, fix)
                else -> LocationState()
            })
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { receive(location, "update") }
            override fun onProviderDisabled(provider: String) { publishAvailability() }
            override fun onProviderEnabled(provider: String) { publishAvailability() }
        }
        publishAvailability()
        for (provider in candidates) {
            try {
                manager.requestLocationUpdates(provider, request.intervalMillis,
                    request.minimumDistanceMeters.toFloat(), listener, Looper.getMainLooper())
                registered += provider
                locationDiagnostic("Continuous updates registered: $provider")
                if (manager.isProviderEnabled(provider)) {
                    manager.getLastKnownLocation(provider)?.let { receive(it, "cache") }
                    val cancellation = CancellationSignal()
                    currentRequests += cancellation
                    manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { location ->
                        if (location != null) receive(location, "current")
                        else locationDiagnostic("Current position request returned no fix: $provider")
                    }
                }
            } catch (error: SecurityException) {
                permissionFailure = true
                locationDiagnostic("Provider permission failure: $provider", error)
            } catch (error: Exception) {
                locationDiagnostic("Provider registration failed: $provider", error)
            }
        }
        if (registered.isEmpty()) trySend(LocationState(
            if (permissionFailure) LocationStatus.PERMISSION_DENIED else LocationStatus.UNAVAILABLE,
        ))
        awaitClose {
            currentRequests.forEach { it.cancel() }
            manager.removeUpdates(listener)
            locationDiagnostic("Subscription stopped; released providers: $registered")
        }
    }
}

internal actual fun locationTimeMillis(): Long = System.currentTimeMillis()

internal actual fun locationDiagnostic(message: String, error: Throwable?) {
    if (error == null) Log.d("BmapsLocation", message) else Log.e("BmapsLocation", message, error)
}

@Composable
actual fun rememberLocationAccess(onChanged: () -> Unit): LocationAccess {
    val context = LocalContext.current
    val changed by rememberUpdatedState(onChanged)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { changed() }
    return remember(context, launcher) {
        object : LocationAccess {
            override fun requestPermission(openSettingsIfDenied: Boolean) {
                if (context.preciseLocationGranted()) changed()
                else if (openSettingsIfDenied) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            override fun openSettings() { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
    }
}
