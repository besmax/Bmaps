/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.gms.location.LocationRequest as GoogleLocationRequest

internal class GoogleFusedLocationSubscription(
    private val context: Context,
    private val onLocation: (Location) -> Unit,
    private val onRegistrationFailure: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val currentRequest = CancellationTokenSource()
    private var client: FusedLocationProviderClient? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!closed.get()) result.locations.forEach { onLocation(it) }
        }
    }

    fun start(request: LocationRequest): Boolean {
        try {
            val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (availability != ConnectionResult.SUCCESS) {
                locationDiagnostic("Google fused unavailable: Play services status=$availability")
                return false
            }
            val fused = LocationServices.getFusedLocationProviderClient(context)
            client = fused
            val nativeRequest = GoogleLocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, request.intervalMillis)
                .setMinUpdateIntervalMillis(request.intervalMillis)
                .setMinUpdateDistanceMeters(request.minimumDistanceMeters.toFloat())
                .setMaxUpdateAgeMillis(30_000)
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(nativeRequest, callback, Looper.getMainLooper())
                .addOnSuccessListener(context.mainExecutor) {
                    if (closed.get()) removeUpdates()
                    else locationDiagnostic("Continuous updates registered: google-fused")
                }
                .addOnFailureListener(context.mainExecutor) { error ->
                    if (!closed.get()) {
                        locationDiagnostic("Google fused registration failed; native providers remain active", error)
                        onRegistrationFailure()
                    }
                }
            requestInitialFixes(fused)
            return true
        } catch (error: Exception) {
            locationDiagnostic("Google fused startup failed; native providers remain active", error)
            return false
        }
    }

    private fun requestInitialFixes(fused: FusedLocationProviderClient) {
        try {
            fused.lastLocation
                .addOnSuccessListener(context.mainExecutor) { location ->
                    if (!closed.get() && location != null) onLocation(location)
                }
                .addOnFailureListener(context.mainExecutor) { error ->
                    if (!closed.get()) locationDiagnostic("Google fused cached position failed", error)
                }
        } catch (error: Exception) {
            locationDiagnostic("Google fused cached position request failed", error)
        }
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, currentRequest.token)
                .addOnSuccessListener(context.mainExecutor) { location ->
                    if (!closed.get()) {
                        if (location != null) onLocation(location)
                        else locationDiagnostic("Google fused current position returned no fix; native providers remain active")
                    }
                }
                .addOnFailureListener(context.mainExecutor) { error ->
                    if (!closed.get()) locationDiagnostic("Google fused current position failed; native providers remain active", error)
                }
        } catch (error: Exception) {
            locationDiagnostic("Google fused current position request failed", error)
        }
    }

    fun close() {
        closed.set(true)
        currentRequest.cancel()
        removeUpdates()
    }

    private fun removeUpdates() {
        try {
            client?.removeLocationUpdates(callback)?.addOnFailureListener(context.mainExecutor) { error ->
                locationDiagnostic("Google fused removal failed", error)
            }
        } catch (error: Exception) {
            locationDiagnostic("Google fused removal failed", error)
        }
    }
}
