/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.plrapps.mapcompose.api.addCallout
import ovh.plrapps.mapcompose.api.moveCallout
import ovh.plrapps.mapcompose.api.removeCallout

@Composable
fun RasterMapCallout(
    renderer: RasterMapRenderer,
    id: String,
    position: MapPoint,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state by renderer.state.collectAsStateWithLifecycle()
    val currentContent by rememberUpdatedState(content)
    val currentModifier by rememberUpdatedState(modifier)
    val engine = state.engine
    DisposableEffect(renderer, engine, id) {
        engine?.addCallout(
            id, position.x, position.y,
            relativeOffset = Offset.Zero,
            zIndex = Float.MAX_VALUE,
            // Keep the native callout attached until its content finishes the exit animation.
            autoDismiss = false,
            clickable = false,
        ) {
            Box(
                currentModifier
                    .onGloballyPositioned { renderer.calloutTouchBounds[id] = it.boundsInRoot() }
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            ) { currentContent() }
        }
        onDispose {
            engine?.removeCallout(id)
            renderer.calloutTouchBounds.remove(id)
        }
    }
    LaunchedEffect(engine, id, position) { engine?.moveCallout(id, position.x, position.y) }
}
