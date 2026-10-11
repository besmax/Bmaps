/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val NETWORK_CONFLICT_MAX_ACCURACY_METERS = 250.0
private const val SOURCE_CONFLICT_MIN_DISTANCE_METERS = 5_000.0
private const val SOURCE_CONFLICT_ACCURACY_MULTIPLIER = 5.0
private const val SOURCE_CONFLICT_TRAVEL_METERS_PER_SECOND = 100.0

internal data class SelectedLocationFix(val source: String, val fix: LocationFix, val sourceConflict: Boolean = false)

internal class LocationFixSelector {
    private val fixes = mutableMapOf<String, LocationFix>()
    private var selected: SelectedLocationFix? = null
    private val quarantined = mutableSetOf<String>()
    val conflictingSources: Set<String> get() = quarantined.toSet()

    fun record(source: String, fix: LocationFix, nowMillis: Long): Boolean {
        if (!fix.isValid() || !fix.isFresh(nowMillis)) return false
        val previous = fixes[source]
        if (previous != null && (fix.timestampMillis < previous.timestampMillis ||
                (fix.timestampMillis == previous.timestampMillis && fix.accuracyMeters >= previous.accuracyMeters))) {
            return false
        }
        fixes[source] = fix
        return true
    }

    fun remove(source: String) {
        fixes.remove(source)
    }

    fun select(nowMillis: Long): SelectedLocationFix? {
        fixes.entries.removeAll { !it.value.isFresh(nowMillis) }
        fixes["network"]?.takeIf { it.accuracyMeters <= NETWORK_CONFLICT_MAX_ACCURACY_METERS }?.let { network ->
            for (source in listOf("gps", "fused", "google-fused")) {
                val satellite = fixes[source] ?: continue
                val timeDifferenceSeconds = abs(satellite.timestampMillis - network.timestampMillis) / 1_000.0
                val allowedDistance = maxOf(SOURCE_CONFLICT_MIN_DISTANCE_METERS,
                    SOURCE_CONFLICT_ACCURACY_MULTIPLIER * (satellite.accuracyMeters + network.accuracyMeters) +
                        SOURCE_CONFLICT_TRAVEL_METERS_PER_SECOND * timeDifferenceSeconds)
                if (locationDistanceMeters(satellite, network) > allowedDistance) quarantined += source
                else quarantined -= source
            }
        }
        val conflict = quarantined.isNotEmpty()
        val current = selected?.takeIf { it.fix.isFresh(nowMillis) && it.source in fixes && it.source !in quarantined }
        val candidates = fixes.filterKeys { it !in quarantined }
            .map { SelectedLocationFix(it.key, it.value, conflict) } + listOfNotNull(current?.copy(sourceConflict = conflict))
        val newest = candidates.maxOfOrNull { it.fix.timestampMillis } ?: return null
        val movementMetersPerSecond = maxOf(2.0, candidates.maxOfOrNull { it.fix.speedMetersPerSecond ?: 0.0 } ?: 0.0)
        fun score(candidate: SelectedLocationFix): Double {
            val lagMillis = (newest - candidate.fix.timestampMillis - LOCATION_ACCURACY_WINDOW_MILLIS).coerceAtLeast(0)
            return candidate.fix.accuracyMeters + lagMillis / 1_000.0 * movementMetersPerSecond
        }
        val next = candidates.asSequence()
            .filter { canReplaceLocationFix(selected?.fix?.takeIf { fix -> fix.isFresh(nowMillis) }, it.fix, conflict) }
            .minWithOrNull(compareBy<SelectedLocationFix> { score(it) }
                .thenByDescending { it.fix.timestampMillis }
                .thenBy { it.source })
        if (next != null) selected = next
        return next
    }
}

internal fun locationDistanceMeters(first: LocationFix, second: LocationFix): Double {
    val radians = kotlin.math.PI / 180
    val latitudeDifference = (second.latitude - first.latitude) * radians
    val longitudeDifference = (second.longitude - first.longitude) * radians
    val halfChord = sin(latitudeDifference / 2) * sin(latitudeDifference / 2) +
        cos(first.latitude * radians) * cos(second.latitude * radians) *
        sin(longitudeDifference / 2) * sin(longitudeDifference / 2)
    return 6_371_000 * 2 * asin(sqrt(halfChord.coerceIn(0.0, 1.0)))
}
