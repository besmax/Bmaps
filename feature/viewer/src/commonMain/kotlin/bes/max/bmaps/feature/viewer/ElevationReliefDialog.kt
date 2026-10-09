/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ElevationReliefDialog(id: PackageId, style: ElevationReliefStyle?, onDismiss: () -> Unit) {
    val owner = remember(id) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model = metroViewModel<ElevationReliefDialogViewModel>(owner)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.load(style) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(model) { model.events.collect { dismiss() } }
    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = { Text(stringResource(Res.string.relief_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.relief_settings_hint))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ElevationPalette.entries.forEach { palette ->
                        FilterChip(state.palette == palette, { model.palette(palette) }, enabled = !state.submitting,
                            label = { Text(stringResource(palette.label())) })
                    }
                }
                ReliefPalettePicker(state.palette, state.minimumColor, state.maximumColor, !state.submitting, model::minimumColor, model::maximumColor)
                Text(stringResource(Res.string.relief_color_preview), style = MaterialTheme.typography.bodySmall)
                ReliefColorScale(ElevationReliefStyle(ElevationReliefOptions(state.palette,
                    minimumColorPosition = state.minimumColor, maximumColorPosition = state.maximumColor), 0.0, 1.0))
                Row { Checkbox(state.automatic, model::automatic, enabled = !state.submitting); Text(stringResource(Res.string.relief_automatic)) }
                if (!state.automatic) {
                    OutlinedTextField(state.minimum, model::minimum, label = { Text(stringResource(Res.string.relief_minimum)) }, singleLine = true, enabled = !state.submitting)
                    OutlinedTextField(state.maximum, model::maximum, label = { Text(stringResource(Res.string.relief_maximum)) }, singleLine = true, enabled = !state.submitting)
                }
                Text(stringResource(Res.string.relief_disk_hint), style = MaterialTheme.typography.bodySmall)
                if (state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ model.generate(id) }, enabled = !state.submitting) { Text(stringResource(Res.string.relief_generate)) } },
        dismissButton = { TextButton(onDismiss, enabled = !state.submitting) { Text(stringResource(Res.string.layers_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReliefPalettePicker(
    palette: ElevationPalette,
    minimum: Double,
    maximum: Double,
    enabled: Boolean,
    onMinimumChange: (Float) -> Unit,
    onMaximumChange: (Float) -> Unit,
) {
    val minimumLabel = stringResource(Res.string.relief_minimum_color)
    val maximumLabel = stringResource(Res.string.relief_maximum_color)
    val connectorColor = MaterialTheme.colorScheme.outline
    Text(minimumLabel, style = MaterialTheme.typography.bodySmall)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 12.dp).height(16.dp)
                .background(Brush.horizontalGradient(listOf(Color(palette.low), Color(palette.high)))))
            Canvas(Modifier.fillMaxSize()) {
                val inset = 12.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(0f)
                val minX = inset + width * minimum.toFloat()
                val maxX = inset + width * maximum.toFloat()
                drawLine(connectorColor, Offset(minX, 24.dp.toPx()), Offset(minX, 40.dp.toPx()), 2.dp.toPx())
                drawLine(connectorColor, Offset(maxX, 56.dp.toPx()), Offset(maxX, 72.dp.toPx()), 2.dp.toPx())
            }
            Slider(minimum.toFloat(), onMinimumChange, enabled = enabled,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().semantics { contentDescription = minimumLabel },
                thumb = { ReliefPaletteThumb(Color(palette.color(minimum)), enabled) },
                track = { Spacer(Modifier.fillMaxWidth().height(4.dp)) })
            Slider(maximum.toFloat(), onMaximumChange, enabled = enabled,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().semantics { contentDescription = maximumLabel },
                thumb = { ReliefPaletteThumb(Color(palette.color(maximum)), enabled) },
                track = { Spacer(Modifier.fillMaxWidth().height(4.dp)) })
        }
    }
    Text(maximumLabel, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(Res.string.relief_color_selection_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReliefPaletteThumb(color: Color, enabled: Boolean) {
    Box(Modifier.size(24.dp).background(color.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)
        .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
}

@Composable
internal fun ReliefColorScale(style: ElevationReliefStyle, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(16.dp).background(Brush.horizontalGradient(listOf(Color(style.color(style.minimumMeters)), Color(style.color(style.maximumMeters))))))
}

@Composable
internal fun ElevationGenerationControls(job: ElevationGenerationJob?, error: org.jetbrains.compose.resources.StringResource?, onCancel: () -> Unit) {
    if (job?.active == true) {
        Text(stringResource(when {
            job.state == ElevationGenerationState.QUEUED -> Res.string.relief_queued
            job.style == null -> Res.string.relief_scanning
            else -> Res.string.relief_generating
        }))
        if (job.style == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            LinearProgressIndicator(progress = { if (job.totalTiles == 0L) 0f else (job.completedTiles.toDouble() / job.totalTiles).toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(Res.string.relief_progress, job.completedTiles, job.totalTiles))
        }
        TextButton(onCancel) { Text(stringResource(Res.string.relief_cancel_generation)) }
    }
    job?.failure?.let { Text(stringResource(reliefFailure(it)), color = MaterialTheme.colorScheme.error) }
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}

private fun ElevationPalette.label() = when (this) {
    ElevationPalette.GREEN -> Res.string.relief_green
    ElevationPalette.BLUE -> Res.string.relief_blue
    ElevationPalette.AMBER -> Res.string.relief_amber
    ElevationPalette.PURPLE -> Res.string.relief_purple
}
