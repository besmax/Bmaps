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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import bes.max.bmaps.domain.mapbuilder.PackageLayer

@Composable
internal fun ViewerLayersDialog(model: ViewerViewModel, state: ViewerState) {
    AlertDialog(
        onDismissRequest = model::dismissLayers,
        title = { Text(stringResource(Res.string.layers_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(Res.string.layers_hint))
                ViewerLevelSelector(model, state)
                if (state.regionCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(state.regionCount) { region ->
                        FilterChip(
                            selected = state.region == region,
                            onClick = { model.selectRegion(region) },
                            enabled = !state.busy,
                            label = { Text(stringResource(Res.string.layers_region, region + 1)) })
                    }
                }
                state.layerDraft.forEachIndexed { index, layer ->
                    ViewerLayerAppearance(model, state, layer, index)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = model::saveLayers, enabled = !state.busy) {
                Text(
                    stringResource(Res.string.layers_save)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = model::dismissLayers, enabled = !state.busy) {
                Text(
                    stringResource(Res.string.layers_cancel)
                )
            }
        },
    )
}

@Composable
private fun ViewerLevelSelector(model: ViewerViewModel, state: ViewerState) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.automaticAvailable) FilterChip(
            selected = state.selectedLevel == null,
            onClick = { model.selectLevel(null) },
            enabled = !state.busy,
            label = { Text(stringResource(Res.string.layers_automatic_zoom)) })
        state.levels.forEach { level ->
            FilterChip(
                selected = state.selectedLevel == level,
                onClick = { model.selectLevel(level) },
                enabled = !state.busy,
                label = { Text(stringResource(Res.string.viewer_level, level)) })
        }
    }
}

@Composable
private fun ViewerLayerAppearance(
    model: ViewerViewModel,
    state: ViewerState,
    layer: PackageLayer,
    index: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(layer.name, Modifier.weight(1f))
        Checkbox(
            layer.visible,
            {
                model.layerAppearance(layer.id, it, layer.opacity)
                model.previewLayers()
            },
            enabled = !state.busy,
            modifier = Modifier.semantics { contentDescription = layer.name })
    }
    Text(stringResource(Res.string.layer_opacity, (layer.opacity * 100).toInt()))
    Slider(
        layer.opacity.toFloat(),
        { model.layerAppearance(layer.id, layer.visible, it.toDouble()) },
        onValueChangeFinished = model::previewLayers,
        enabled = !state.busy,
        modifier = Modifier.semantics { contentDescription = layer.name })
    Row {
        TextButton(
            onClick = { model.moveLayer(layer.id, -1) },
            enabled = !state.busy && index > 0
        ) { Text(stringResource(Res.string.layer_down)) }
        TextButton(
            onClick = { model.moveLayer(layer.id, 1) },
            enabled = !state.busy && index < state.layerDraft.lastIndex
        ) { Text(stringResource(Res.string.layer_up)) }
    }
    layer.attribution.forEach {
        Text(
            it.text,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
