/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.PackageId
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun ViewerScreen(
    packageId: PackageId,
    onBack: () -> Unit,
    calloutAnimated: Boolean = true,
    autoDismiss: Boolean = true,
) {
    val model = metroViewModel<ViewerViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val position = metroViewModel<MapPositionViewModel>()
    val positionState by position.state.collectAsStateWithLifecycle()
    val annotations = metroViewModel<AnnotationEditorViewModel>()
    val annotationState by annotations.state.collectAsStateWithLifecycle()
    val camera by model.renderer.camera.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    ViewerScreenEffects(packageId, model, position, annotations, state.annotationPyramid, camera, snackbar)

    Box(Modifier.fillMaxSize()) {
        ViewerMapContent(model, state, annotations, annotationState, camera, calloutAnimated, autoDismiss)
        if (positionState.coordinate != null && state.error == null) {
            MapCrosshair(Modifier.align(Alignment.Center))
        }
        ViewerBottomContent(
            model, state, annotations, annotationState, positionState,
            Modifier.align(Alignment.BottomCenter),
        )
        ViewerMapControls(model, state, annotations, onBack)
        state.error?.let { error ->
            ViewerError(
                error = error,
                onRetry = {
                    model.retry()
                    position.open(packageId, model.renderer.camera, retry = true)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    ViewerDialogs(model, state, annotations, annotationState)
}
