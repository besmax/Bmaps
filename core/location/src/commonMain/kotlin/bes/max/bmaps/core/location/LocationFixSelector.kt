/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.location

internal data class SelectedLocationFix(val source: String, val fix: LocationFix)

internal class LocationFixSelector {
    private val fixes = mutableMapOf<String, LocationFix>()
    private var selected: SelectedLocationFix? = null

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
        val newest = fixes.values.maxOfOrNull { it.timestampMillis }
        val current = selected?.fix?.takeIf { it.isFresh(nowMillis) }
        val next = fixes.entries.asSequence()
            .filter { newest != null && newest - it.value.timestampMillis <= LOCATION_ACCURACY_WINDOW_MILLIS }
            .filter { canReplaceLocationFix(current, it.value) }
            .minWithOrNull(compareBy<Map.Entry<String, LocationFix>> { it.value.accuracyMeters }
                .thenByDescending { it.value.timestampMillis }
                .thenBy { it.key })
            ?.let { SelectedLocationFix(it.key, it.value) }
        if (next != null) selected = next
        return next
    }
}
