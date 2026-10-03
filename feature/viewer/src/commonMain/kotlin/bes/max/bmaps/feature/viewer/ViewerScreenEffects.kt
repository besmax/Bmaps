/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.mapengine.MapEvent
import bes.max.bmaps.core.mapengine.MapCameraSnapshot
import bes.max.bmaps.core.mapengine.TilePyramid
import bes.max.bmaps.domain.mapbuilder.PackageId
import org.jetbrains.compose.resources.getString

@Composable
internal fun ViewerScreenEffects(
    packageId: PackageId,
    model: ViewerViewModel,
    position: MapPositionViewModel,
    annotations: AnnotationEditorViewModel,
    pyramid: TilePyramid?,
    camera: MapCameraSnapshot?,
    snackbar: SnackbarHostState,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    LaunchedEffect(annotations, pyramid, camera, density) {
        annotations.cameraChanged(pyramid, camera, density)
    }
    LaunchedEffect(annotations, model) {
        model.renderer.controller.results.collect {
            annotations.cameraResult(it)
            model.locationCameraResult(it)
        }
    }
    DisposableEffect(annotations, model) {
        onDispose {
            annotations.cancelExpansion()
            model.renderer.controller.cancelMove()
        }
    }

    LaunchedEffect(packageId, position, model) { position.open(packageId, model.renderer.camera) }
    LaunchedEffect(packageId, model, annotations) {
        annotations.open(packageId)
        model.open(packageId)
    }
    LaunchedEffect(model, annotations) {
        model.annotationEvents.collect { (pyramid, event) ->
            if (event !is MapEvent.Tap || !model.state.value.immersive) {
                annotations.mapEvent(pyramid, event)
            }
        }
    }
    LaunchedEffect(annotations, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            annotations.events.collect {
                if (!model.state.value.immersive) snackbar.showSnackbar(getString(it))
            }
        }
    }

    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect {
                if (!model.state.value.immersive) snackbar.showSnackbar(getString(it))
            }
        }
    }
}
