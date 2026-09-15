package bes.max.bmaps.feature.viewer

import bmaps.feature.viewer.generated.resources.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val attribution = state.manifest?.layers?.firstOrNull()?.attribution.orEmpty()

    LaunchedEffect(packageId, model) { model.open(packageId) }

    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { snackbar.showSnackbar(getString(it)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        RasterMap(model.renderer, Modifier.fillMaxSize())

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
        if (attribution.isNotEmpty()) MapIconButton(
            onClick = { model.showAttribution(true) },
            iconResId = Res.drawable.ic_info,
            contentDescription = stringResource(Res.string.viewer_attribution),
            modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
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
