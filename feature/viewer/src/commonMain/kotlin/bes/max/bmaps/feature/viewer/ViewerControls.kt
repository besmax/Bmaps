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
    model: ViewerViewModel,
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    annotationState: AnnotationEditorState,
    positionState: MapPositionState,
    modifier: Modifier = Modifier,
) {
    val attribution = state.manifest?.layers.orEmpty().flatMap { it.attribution }.distinct()
    Column(
        modifier.safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier,
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.weight(1f))
            if (attribution.isNotEmpty()) {
                MapIconButton(
                    onClick = { model.showAttribution(true) },
                    iconResId = Res.drawable.ic_info,
                    contentDescription = stringResource(Res.string.viewer_attribution),
                )
            }
        }
        AnnotationEditorContent(annotations, annotationState, Modifier)
        if (state.error == null) MapPositionOverlay(positionState)
    }
}

@Composable
internal fun BoxScope.ViewerMapControls(
    model: ViewerViewModel,
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    onBack: () -> Unit,
) {
    MapIconButton(
        onClick = onBack,
        iconResId = MapIcons.back,
        contentDescription = stringResource(Res.string.viewer_back),
        modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopStart)
    )

    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MapIconButton(
            onClick = { annotations.cancelExpansion(); model.renderer.controller.zoomIn() },
            iconResId = MapIcons.zoomIn,
            contentDescription = stringResource(Res.string.viewer_zoom_in),
        )

        MapIconButton(
            onClick = { annotations.cancelExpansion(); model.renderer.controller.zoomOut() },
            iconResId = MapIcons.zoomOut,
            contentDescription = stringResource(Res.string.viewer_zoom_out),
        )
    }
    MapIconButton(
        onClick = model::showLayers,
        iconResId = MapIcons.layers,
        contentDescription = stringResource(Res.string.layers_title),
        enabled = state.manifest != null && state.error == null,
        modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp)
    )
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
