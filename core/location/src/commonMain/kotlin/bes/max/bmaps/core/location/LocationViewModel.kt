/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import androidx.lifecycle.ViewModel
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface LocationEvent {
    data class Center(val fix: LocationFix, val requestId: Long) : LocationEvent
    data class Message(val status: LocationStatus) : LocationEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LocationViewModel(private val service: LocationService) : ViewModel() {
    private val mutableState = MutableStateFlow(LocationState())
    val state = mutableState.asStateFlow()
    private val channel = Channel<LocationEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val revision = MutableStateFlow(0)
    private var centerPending = false
    private var centerVersion = 0L

    fun refresh() {
        locationDiagnostic("Location refresh requested")
        revision.value++
    }
    fun cancelCenter() { centerPending = false; centerVersion++ }
    fun takeCenter(event: LocationEvent.Center): LocationFix? {
        if (event.requestId != centerVersion || !fresh(event.fix)) {
            locationDiagnostic("Center event ignored: cancelled request or expired fix")
            return null
        }
        locationDiagnostic("Center event delivered to map")
        centerVersion++
        return event.fix
    }
    fun center() {
        centerPending = false
        centerVersion++
        val fix = state.value.fix
        locationDiagnostic("Center requested: status=${state.value.status}, hasFix=${fix != null}")
        if (fix != null && fresh(fix)) channel.trySend(LocationEvent.Center(fix, centerVersion))
        else {
            centerPending = true
            channel.trySend(LocationEvent.Message(state.value.status))
            if (state.value.status in setOf(LocationStatus.UNAVAILABLE, LocationStatus.TIMED_OUT, LocationStatus.INVALID_FIX)) refresh()
        }
    }

    private fun publish(update: LocationState) {
        val previous = mutableState.value
        if (previous.status != update.status) {
            locationDiagnostic("Location status: ${previous.status} -> ${update.status}")
            if (update.status !in setOf(LocationStatus.WAITING, LocationStatus.ACTIVE)) {
                channel.trySend(LocationEvent.Message(update.status))
            }
        }
        mutableState.value = update
    }

    suspend fun track(request: LocationRequest = LocationRequest()): Unit = coroutineScope {
        locationDiagnostic("Map location tracking resumed")
        var waitingSince = kotlin.time.TimeSource.Monotonic.markNow()
        try {
            launch {
                revision.collectLatest {
                    publish(LocationState())
                    waitingSince = kotlin.time.TimeSource.Monotonic.markNow()
                    try {
                        service.observe(request).collect { update ->
                            val fix = update.fix
                            when {
                                fix == null -> {
                                    if (update.status == LocationStatus.WAITING && state.value.status != LocationStatus.WAITING) {
                                        waitingSince = kotlin.time.TimeSource.Monotonic.markNow()
                                    }
                                    publish(update)
                                }
                                !valid(fix) -> {
                                    locationDiagnostic("Fix rejected by presentation: invalid coordinates or accuracy")
                                    if (state.value.fix == null) publish(LocationState(LocationStatus.INVALID_FIX))
                                }
                                !fresh(fix) -> {
                                    locationDiagnostic("Fix rejected by presentation: ageMs=${locationTimeMillis() - fix.timestampMillis}")
                                    if (state.value.fix == null) publish(LocationState(LocationStatus.STALE))
                                }
                                fix.timestampMillis < (state.value.fix?.timestampMillis ?: Long.MIN_VALUE) -> {
                                    locationDiagnostic("Fix ignored by presentation: out of order")
                                }
                                else -> {
                                    publish(update)
                                    locationDiagnostic("Fresh position accepted: accuracyM=${fix.accuracyMeters}")
                                    if (centerPending) {
                                        centerPending = false
                                        channel.send(LocationEvent.Center(fix, centerVersion))
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        locationDiagnostic("Location subscription failed", error)
                        publish(LocationState(LocationStatus.UNAVAILABLE))
                    }
                }
            }
            while (true) {
                delay(1_000)
                val current = state.value
                when {
                    current.fix?.let { !fresh(it) } == true -> publish(LocationState(LocationStatus.STALE))
                    current.status == LocationStatus.WAITING && waitingSince.elapsedNow().inWholeMilliseconds >= 30_000 -> {
                        publish(LocationState(LocationStatus.TIMED_OUT))
                    }
                }
            }
        } finally {
            locationDiagnostic("Map location tracking stopped")
            cancelCenter()
            mutableState.value = LocationState()
        }
    }

    private fun fresh(fix: LocationFix) = locationTimeMillis() - fix.timestampMillis in -5_000L..30_000L
    private fun valid(fix: LocationFix) =
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
            fix.accuracyMeters.isFinite() && fix.accuracyMeters >= 0
}
