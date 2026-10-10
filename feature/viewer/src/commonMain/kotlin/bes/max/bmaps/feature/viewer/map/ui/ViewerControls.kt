/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.map.ui

import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationEditorState
import bes.max.bmaps.feature.viewer.annotations.presentation.AnnotationEditorViewModel
import bes.max.bmaps.feature.viewer.annotations.ui.AnnotationEditorContent
import bes.max.bmaps.feature.viewer.position.presentation.MapPositionState
import bes.max.bmaps.feature.viewer.position.ui.MapPositionOverlay
import bes.max.bmaps.feature.viewer.map.presentation.ViewerState
import bes.max.bmaps.feature.viewer.map.presentation.ViewerViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import bes.max.bmaps.core.ui.components.MapIconButton
import bes.max.bmaps.core.ui.components.MapIcons
import org.jetbrains.compose.resources.StringResource

@Composable
internal fun ViewerBottomContent(
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    annotationState: AnnotationEditorState,
    positionState: MapPositionState,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().safeDrawingPadding().padding(
            horizontal = 80.dp,
            vertical = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnnotationEditorContent(annotations, annotationState, Modifier)
        if (state.error == null) MapPositionOverlay(positionState, singleLine = singleLine)
    }
}

@Composable
internal fun BoxScope.ViewerMapControls(
    model: ViewerViewModel,
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    onBack: () -> Unit,
    landscape: Boolean,
    onLocation: () -> Unit = {},
    locationUnavailable: Boolean = false,
    locationDescription: String? = null,
    onInteraction: () -> Unit = {},
) {
    MapIconButton(
        onClick = onBack,
        iconResId = MapIcons.back,
        contentDescription = stringResource(Res.string.viewer_back),
        modifier = Modifier.safeDrawingPadding().padding(16.dp).align(Alignment.TopStart)
    )

    val layers: @Composable () -> Unit = {
        MapIconButton(
            onClick = { onInteraction(); model.showLayers() },
            iconResId = MapIcons.layers,
            contentDescription = stringResource(Res.string.layers_title),
            enabled = state.manifest != null && state.error == null,
        )
    }
    val zoom: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapIconButton(
                onClick = { onInteraction(); annotations.cancelExpansion(); model.renderer.controller.zoomIn() },
                iconResId = MapIcons.zoomIn,
                contentDescription = stringResource(Res.string.viewer_zoom_in),
            )
            MapIconButton(
                onClick = { onInteraction(); annotations.cancelExpansion(); model.renderer.controller.zoomOut() },
                iconResId = MapIcons.zoomOut,
                contentDescription = stringResource(Res.string.viewer_zoom_out),
            )
        }
    }
    val other: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapIconButton(
                onClick = { annotations.cancelExpansion(); onLocation() },
                iconResId = if (locationUnavailable) MapIcons.myLocationOff else MapIcons.myLocation,
                iconTint = if (locationUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                contentDescription = locationDescription ?: stringResource(Res.string.my_location),
                enabled = state.manifest != null && state.error == null,
            )
            if (state.manifest?.layers.orEmpty().any { it.attribution.isNotEmpty() }) {
                MapIconButton(
                    onClick = { model.showAttribution(true) },
                    iconResId = Res.drawable.ic_info,
                    contentDescription = stringResource(Res.string.viewer_attribution),
                )
            }
        }
    }
    val rail = Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(16.dp).width(48.dp)
    if (landscape) {
        Column(
            rail.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            layers()
            zoom()
            other()
        }
    } else {
        Box(rail.fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter)) { layers() }
            Box(Modifier.align(Alignment.Center)) { zoom() }
            Box(Modifier.align(Alignment.BottomCenter)) { other() }
        }
    }
}

@Composable
internal fun ViewerError(
    error: StringResource,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.safeDrawingPadding().padding(16.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(error))
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.viewer_retry)) }
        }
    }
}
