package bes.max.bmaps.feature.viewer

import bmaps.feature.viewer.generated.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.mapengine.RasterMap
import bes.max.bmaps.core.ui.components.MapIconButton
import bes.max.bmaps.core.ui.components.MapIcons
import bes.max.bmaps.domain.mapbuilder.*
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

@Composable
fun ViewerScreen(packageId: PackageId, onBack: () -> Unit) {
    val model = metroViewModel<ViewerViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val annotations = metroViewModel<AnnotationEditorViewModel>()
    val annotationState by annotations.state.collectAsStateWithLifecycle()
    val overlays = remember(annotationState.items, annotationState.layers, annotationState.draft, state.annotationPyramid) {
        annotationOverlays(annotationState, state.annotationPyramid)
    }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val attribution = state.manifest?.layers.orEmpty().flatMap { it.attribution }.distinct()

    LaunchedEffect(packageId, model, annotations) { annotations.open(packageId); model.open(packageId) }
    LaunchedEffect(model, annotations) { model.annotationEvents.collect { (pyramid, event) -> annotations.mapEvent(pyramid, event) } }
    LaunchedEffect(annotations, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            annotations.events.collect { snackbar.showSnackbar(getString(it)) }
        }
    }

    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { snackbar.showSnackbar(getString(it)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        RasterMap(model.renderer, Modifier.fillMaxSize(), overlays.markers, overlays.paths, annotations::select) { marker ->
            Box(Modifier.size(48.dp).clickable(role = Role.Button) { annotations.select(marker.id, marker.position) }, contentAlignment = Alignment.BottomCenter) {
                MarkerIcon(marker.icon, marker.color, marker.label.ifBlank { stringResource(Res.string.annotations_place) }, Modifier.size(36.dp))
            }
        }
        if (annotationState.draft == null) FilledTonalButton(
            onClick = { annotations.panel(true) },
            enabled = state.manifest != null && state.error == null && !annotationState.busy,
            modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp),
        ) { Text(stringResource(Res.string.annotations_title)) }
        AnnotationEditorContent(annotations, annotationState,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))

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
                onClick = model.renderer.controller::zoomIn,
                iconResId = MapIcons.zoomIn,
                contentDescription = stringResource(Res.string.viewer_zoom_in),
            )

            MapIconButton(
                onClick = model.renderer.controller::zoomOut,
                iconResId = MapIcons.zoomOut,
                contentDescription = stringResource(Res.string.viewer_zoom_out),
            )
        }
        MapIconButton(onClick = model::showLayers,
            iconResId = MapIcons.layers,
            contentDescription = stringResource(Res.string.layers_title),
            enabled = state.manifest != null && state.error == null,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp))
        state.error?.let { error ->
            Surface(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(error))
                    TextButton(onClick = model::retry) { Text(stringResource(Res.string.viewer_retry)) }
                }
            }
        }
        if (attribution.isNotEmpty()) MapIconButton(
            onClick = { model.showAttribution(true) },
            iconResId = Res.drawable.ic_info,
            contentDescription = stringResource(Res.string.viewer_attribution),
            modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (state.layersVisible) AlertDialog(
        onDismissRequest = model::dismissLayers,
        title = { Text(stringResource(Res.string.layers_title)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.layers_hint))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.automaticAvailable) FilterChip(selected = state.selectedLevel == null,
                    onClick = { model.selectLevel(null) }, enabled = !state.busy, label = { Text(stringResource(Res.string.layers_automatic_zoom)) })
                state.levels.forEach { level ->
                    FilterChip(selected = state.selectedLevel == level, onClick = { model.selectLevel(level) }, enabled = !state.busy,
                        label = { Text(stringResource(Res.string.viewer_level, level)) })
                }
            }
            if (state.regionCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(state.regionCount) { region ->
                    FilterChip(selected = state.region == region, onClick = { model.selectRegion(region) }, enabled = !state.busy,
                        label = { Text(stringResource(Res.string.layers_region, region + 1)) })
                }
            }
            state.layerDraft.forEachIndexed { index, layer ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(layer.name, Modifier.weight(1f))
                    Checkbox(layer.visible, { model.layerAppearance(layer.id, it, layer.opacity); model.previewLayers() }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = layer.name })
                }
                Text(stringResource(Res.string.layer_opacity, (layer.opacity * 100).toInt()))
                Slider(layer.opacity.toFloat(), { model.layerAppearance(layer.id, layer.visible, it.toDouble()) },
                    onValueChangeFinished = model::previewLayers, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = layer.name })
                Row {
                    TextButton(onClick = { model.moveLayer(layer.id, -1) }, enabled = !state.busy && index > 0) { Text(stringResource(Res.string.layer_down)) }
                    TextButton(onClick = { model.moveLayer(layer.id, 1) }, enabled = !state.busy && index < state.layerDraft.lastIndex) { Text(stringResource(Res.string.layer_up)) }
                }
                layer.attribution.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
            }
        } },
        confirmButton = { TextButton(onClick = model::saveLayers, enabled = !state.busy) { Text(stringResource(Res.string.layers_save)) } },
        dismissButton = { TextButton(onClick = model::dismissLayers, enabled = !state.busy) { Text(stringResource(Res.string.layers_cancel)) } },
    )
    if (state.attributionVisible) AlertDialog(
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
    if (state.details) AlertDialog(
        onDismissRequest = { model.showDetails(false) },
        title = { Text(state.summary?.name ?: stringResource(Res.string.viewer_details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.manifest?.let { manifest ->
                    Text(stringResource(Res.string.viewer_bounds, manifest.bounds.south.toString(), manifest.bounds.west.toString(),
                        manifest.bounds.north.toString(), manifest.bounds.east.toString()))
                    Text(stringResource(Res.string.viewer_levels, state.levels.joinToString()))
                    Text(stringResource(if (manifest.elevation == null) Res.string.viewer_no_elevation else Res.string.viewer_elevation))
                }
                state.summary?.let { Text(stringResource(Res.string.viewer_size, it.sizeBytes)) }
                Text(stringResource(Res.string.viewer_avatar), style = MaterialTheme.typography.titleSmall)
                MapAvatar.entries.chunked(2).forEach { avatars ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        avatars.forEach { avatar ->
                            FilterChip(selected = state.summary?.avatarKey == avatar.storageKey, enabled = !state.busy,
                                onClick = { model.avatar(avatar) },
                                leadingIcon = { Icon(painterResource(avatarIcon(avatar)), null, Modifier.size(20.dp)) }, label = { Text(stringResource(avatarLabel(avatar))) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { model.showDetails(false) }) { Text(stringResource(Res.string.viewer_done)) } },
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
