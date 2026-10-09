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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import bes.max.bmaps.domain.mapbuilder.MapAvatar
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun ViewerDialogs(
    model: ViewerViewModel,
    state: ViewerState,
    annotations: AnnotationEditorViewModel,
    annotationState: AnnotationEditorState,
    progress: ElevationProgressState,
    onCancelGeneration: () -> Unit,
) {
    if (annotationState.clusterMembers.isNotEmpty()) ViewerClusterDialog(annotations, annotationState)
    if (state.layersVisible) ViewerLayersDialog(model, state, progress, onCancelGeneration)
    if (state.reliefDialogVisible) state.manifest?.let { manifest ->
        ElevationReliefDialog(manifest.id, manifest.layers.firstOrNull { it.elevationRelief != null }?.elevationRelief, model::dismissReliefSettings)
    }
    if (state.attributionVisible) ViewerAttributionDialog(model, state)
    if (state.details) ViewerDetailsDialog(model, state)
}

@Composable
private fun ViewerClusterDialog(annotations: AnnotationEditorViewModel, annotationState: AnnotationEditorState) {
    AlertDialog(
        onDismissRequest = annotations::dismissSelection,
        title = {
            Text(
                stringResource(
                    Res.string.annotations_cluster_title,
                    annotationState.clusterMembers.size
                )
            )
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item { Text(stringResource(Res.string.annotations_cluster_hint)) }
                items(annotationState.clusterMembers, key = { it.id }) { item ->
                    TextButton({ annotations.select(item.id) }) {
                        Text(
                            listOf(
                                item.name,
                                stringResource(item.kind.label())
                            ).filter { it.isNotBlank() }.joinToString(" · ")
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(annotations::dismissSelection) { Text(stringResource(Res.string.viewer_done)) } },
    )
}

@Composable
private fun ViewerAttributionDialog(model: ViewerViewModel, state: ViewerState) {
    val uriHandler = LocalUriHandler.current
    val attribution = state.manifest?.layers.orEmpty().flatMap { it.attribution }.distinct()
    AlertDialog(
        onDismissRequest = { model.showAttribution(false) },
        title = { Text(stringResource(Res.string.viewer_attribution)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.viewer_attribution_hint))
                attribution.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        Text(entry.url, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            model.showAttribution(false)
                            model.openLink(entry.url, uriHandler::openUri)
                        }) {
                            Text(stringResource(Res.string.viewer_open_link))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { model.showAttribution(false) }) {
                Text(stringResource(Res.string.viewer_done))
            }
        },
    )
}

@Composable
private fun ViewerDetailsDialog(model: ViewerViewModel, state: ViewerState) {
    AlertDialog(
        onDismissRequest = { model.showDetails(false) },
        title = { Text(state.summary?.name ?: stringResource(Res.string.viewer_details)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.manifest?.let { manifest ->
                    Text(
                        stringResource(
                            Res.string.viewer_bounds,
                            manifest.bounds.south.toString(),
                            manifest.bounds.west.toString(),
                            manifest.bounds.north.toString(),
                            manifest.bounds.east.toString()
                        )
                    )
                    Text(stringResource(Res.string.viewer_levels, state.levels.joinToString()))
                    Text(stringResource(if (manifest.elevation == null) Res.string.viewer_no_elevation else Res.string.viewer_elevation))
                }
                state.summary?.let { Text(stringResource(Res.string.viewer_size, it.sizeBytes)) }
                Text(
                    stringResource(Res.string.viewer_avatar),
                    style = MaterialTheme.typography.titleSmall
                )
                MapAvatar.entries.chunked(2).forEach { avatars ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        avatars.forEach { avatar ->
                            FilterChip(
                                selected = state.summary?.avatarKey == avatar.storageKey,
                                enabled = !state.busy,
                                onClick = { model.avatar(avatar) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(avatarIcon(avatar)),
                                        null,
                                        Modifier.size(20.dp)
                                    )
                                },
                                label = { Text(stringResource(avatarLabel(avatar))) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { model.showDetails(false) }) {
                Text(
                    stringResource(
                        Res.string.viewer_done
                    )
                )
            }
        },
    )
}

private fun avatarLabel(avatar: MapAvatar) = when (avatar) {
    MapAvatar.MAP -> Res.string.avatar_map
    MapAvatar.MOUNTAIN -> Res.string.avatar_mountain
    MapAvatar.FOREST -> Res.string.avatar_forest
    MapAvatar.WATER -> Res.string.avatar_water
    MapAvatar.CITY -> Res.string.avatar_city
    MapAvatar.CAMP -> Res.string.avatar_camp
}

private fun avatarIcon(avatar: MapAvatar) = when (avatar) {
    MapAvatar.MAP -> Res.drawable.avatar_map
    MapAvatar.MOUNTAIN -> Res.drawable.avatar_mountain
    MapAvatar.FOREST -> Res.drawable.avatar_forest
    MapAvatar.WATER -> Res.drawable.avatar_water
    MapAvatar.CITY -> Res.drawable.avatar_city
    MapAvatar.CAMP -> Res.drawable.avatar_camp
}
