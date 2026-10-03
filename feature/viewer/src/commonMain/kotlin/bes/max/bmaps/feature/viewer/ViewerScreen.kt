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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.PackageId
import bes.max.bmaps.core.location.*
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.mapengine.WebMercator
import bes.max.bmaps.core.mapengine.currentLocationOverlays
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
    val location = metroViewModel<LocationViewModel>()
    val locationState by location.state.collectAsStateWithLifecycle()
    val locationAccess = LocationTracking(location, onStopped = model::cancelLocationMove)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locationLabel = stringResource(Res.string.my_location)
    val coordinate = locationState.fix?.let { GeographicCoordinate(it.latitude, it.longitude) }?.takeIf { fix ->
        state.manifest?.layers?.firstOrNull()?.bounds?.let { bounds ->
            WebMercator.splitBounds(bounds).orEmpty().any {
                fix.latitude in it.south..it.north && fix.longitude in it.west..it.east
            }
        } == true
    }
    val locationOverlays = remember(coordinate, locationState.fix?.accuracyMeters, state.annotationPyramid, locationLabel) {
        currentLocationOverlays(coordinate, locationState.fix?.accuracyMeters, state.annotationPyramid, locationLabel)
    }
    LaunchedEffect(location, lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            location.events.collect { event ->
                when (event) {
                    is LocationEvent.Center -> location.takeCenter(event)?.let {
                        model.centerLocation(GeographicCoordinate(it.latitude, it.longitude))
                    }
                    is LocationEvent.Message -> if (!model.state.value.immersive) {
                        launch { snackbar.showSnackbar(getString(locationMessage(event.status))) }
                    }
                }
            }
        }
    }

    ViewerScreenEffects(packageId, model, position, annotations, state.annotationPyramid, camera, snackbar)

    LaunchedEffect(state.immersive) {
        if (state.immersive) snackbar.currentSnackbarData?.dismiss()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        ViewerMapContent(model, state, annotations, annotationState, camera, calloutAnimated, autoDismiss,
            locationOverlays, location::cancelCenter)
        if (!state.immersive) {
            if (positionState.coordinate != null && state.error == null) {
                MapCrosshair(Modifier.align(Alignment.Center))
            }
            ViewerBottomContent(
                state, annotations, annotationState, positionState,
                Modifier.align(Alignment.BottomCenter),
                singleLine = maxWidth > maxHeight,
            )
            LocationNotice(locationState, locationAccess, location,
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(horizontal = 80.dp, vertical = 16.dp).widthIn(max = 360.dp),
                outsideCoverage = locationState.fix != null && state.manifest != null && coordinate == null,
            )
            ViewerMapControls(model, state, annotations, onBack,
                landscape = maxWidth > maxHeight,
                onLocation = {
                    when (locationState.status) {
                        LocationStatus.PERMISSION_DENIED, LocationStatus.PRECISE_PERMISSION_REQUIRED -> locationAccess.requestPermission(openSettingsIfDenied = true)
                        LocationStatus.DISABLED -> locationAccess.openSettings()
                        else -> location.center()
                    }
                },
                onInteraction = { location.cancelCenter(); model.cancelLocationMove() },
            )
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
    }
    if (!state.immersive) ViewerDialogs(model, state, annotations, annotationState)
}
