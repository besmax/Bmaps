/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun viewerImmersiveGesture(immersive: Boolean, onToggle: () -> Unit): Modifier {
    val toggle by rememberUpdatedState(onToggle)
    val actionLabel = stringResource(
        if (immersive) Res.string.viewer_show_controls else Res.string.viewer_hide_controls
    )
    return Modifier.semantics {
        customActions = listOf(CustomAccessibilityAction(actionLabel) { toggle(); true })
    }.pointerInput(Unit) {
        val distance = maxOf(48.dp.toPx(), viewConfiguration.touchSlop * 3)
        val separationTolerance = viewConfiguration.touchSlop * 2
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var origins: Map<PointerId, Offset>? = null
            var rejected = false
            var triggered = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.filter { it.pressed }
                if (triggered) {
                    event.changes.forEach { it.consume() }
                } else if (!rejected) {
                    val start = origins
                    when {
                        fingers.size > 2 -> rejected = true
                        start != null && (fingers.size != 2 || fingers.any { it.id !in start }) -> rejected = true
                        fingers.size == 2 && start == null -> origins = fingers.associate { it.id to it.position }
                        fingers.size == 2 && start != null -> {
                            val first = fingers[0].position - start.getValue(fingers[0].id)
                            val second = fingers[1].position - start.getValue(fingers[1].id)
                            if ((first - second).getDistance() > separationTolerance ||
                                abs(first.x) > distance || abs(second.x) > distance
                            ) {
                                rejected = true
                            } else if (abs(first.y) >= distance && abs(second.y) >= distance &&
                                first.y * second.y > 0 &&
                                abs(first.x) < abs(first.y) / 2 && abs(second.x) < abs(second.y) / 2
                            ) {
                                triggered = true
                                event.changes.forEach { it.consume() }
                                toggle()
                            }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
