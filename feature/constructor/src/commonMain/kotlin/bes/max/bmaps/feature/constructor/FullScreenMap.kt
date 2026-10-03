/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.location.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import bes.max.bmaps.core.ui.components.MapIconButton
import bes.max.bmaps.core.ui.components.MapIcons
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.painterResource

@Composable
fun FullScreenMap(
    provider: String,
    style: String,
    showFixture: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onCredentials: (String) -> Unit
) {
    val model = metroViewModel<OnlineMapViewModel>()
    val area = metroViewModel<AreaSelectionViewModel>()
    val location = metroViewModel<LocationViewModel>()
    val locationState by location.state.collectAsStateWithLifecycle()
    val locationAccess = LocationTracking(location, enabled = !showFixture, onStopped = model::cancelLocationCenter)
    val camera by model.renderer.camera.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val locationLabel = stringResource(Res.string.my_location)
    val coordinate = locationState.fix?.let { GeographicCoordinate(it.latitude, it.longitude) }
    val locationOverlays = remember(coordinate, locationState.fix?.accuracyMeters, camera?.pyramid, locationLabel) {
        currentLocationOverlays(coordinate, locationState.fix?.accuracyMeters, camera?.pyramid, locationLabel)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val selection by area.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controls = model.renderer.controller
    val uri = LocalUriHandler.current
    val settings by rememberUpdatedState(onSettings)
    val ready =
        state.selected?.id?.let { it.provider.value == provider && it.style.value == style } == true
    LaunchedEffect(provider, style, showFixture, state.choices) {
        model.display(provider, style, showFixture)
    }
    LaunchedEffect(area, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { area.events.collect { settings() } }
    }
    LaunchedEffect(state.visibleWindow) { area.updateWindow(state.visibleWindow) }
    LaunchedEffect(model, locationState.fix, camera, ready, showFixture) {
        if (ready && !showFixture) locationState.fix?.let {
            model.focusInitially(GeographicCoordinate(it.latitude, it.longitude), it.accuracyMeters)
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { message -> launch { snackbar.showSnackbar(getString(message)) } }
        }
    }
    LaunchedEffect(location, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            location.events.collect { event ->
                when (event) {
                    is LocationEvent.Center -> location.takeCenter(event)?.let {
                        model.requestLocationCenter(GeographicCoordinate(it.latitude, it.longitude))
                    }
                    is LocationEvent.Message -> launch { snackbar.showSnackbar(getString(locationMessage(event.status))) }
                }
            }
        }
    }
    val interacted = { model.interacted(); location.cancelCenter() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("full-screen-map")) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        if (ready) {
            RasterMap(
                model.renderer,
                Modifier.fillMaxSize().testTag(if (showFixture) "sample-map" else "online-map"),
                markers = locationOverlays.markers,
                paths = locationOverlays.paths,
                onGestureStart = interacted,
            ) { CurrentLocationMarker(locationLabel) }
        }
        if (!showFixture) LocationNotice(locationState, locationAccess, location,
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(horizontal = 80.dp, vertical = 16.dp).widthIn(max = 360.dp))
        if (selection.selecting) SelectionFrame(selection, area::startDrawing, area::draw, area::finishDrawing, area::cancelDrawing)
        Row(
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(margin),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapIconButton(
                onClick = onBack,
                iconResId = MapIcons.back,
                contentDescription = stringResource(Res.string.back)
            )
            if (selection.selecting) MapButton(
                stringResource(Res.string.cancel_selection),
                area::cancel
            )
        }
        if (!selection.selecting) Column(
            Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(margin),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapIconButton(
                onClick = { interacted(); controls.zoomIn() },
                iconResId = MapIcons.zoomIn,
                contentDescription = stringResource(Res.string.zoom_in)
            )

            MapIconButton(
                onClick = { interacted(); controls.zoomOut() },
                iconResId = MapIcons.zoomOut,
                contentDescription = stringResource(Res.string.zoom_out)
            )
            if (!showFixture) MapIconButton(
                onClick = {
                    when (locationState.status) {
                        LocationStatus.PERMISSION_DENIED, LocationStatus.PRECISE_PERMISSION_REQUIRED -> locationAccess.requestPermission(openSettingsIfDenied = true)
                        LocationStatus.DISABLED -> locationAccess.openSettings()
                        else -> location.center()
                    }
                },
                iconResId = MapIcons.myLocation,
                contentDescription = locationLabel,
            )
        }
        Column(
            Modifier.align(if (maxWidth < 600.dp) Alignment.BottomCenter else Alignment.BottomEnd)
                .safeDrawingPadding().padding(start = margin, end = margin, bottom = 64.dp)
                .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selection.selecting) {
                MapButton(
                    stringResource(Res.string.accept),
                    area::accept,
                    enabled = selection.bounds != null,
                    confirmation = true
                )
            } else {
                MapIconButton(
                    onClick = { interacted(); area.choose() },
                    iconResId = Res.drawable.ic_crop_area,
                    contentDescription = stringResource(Res.string.choose_area),
                )
            }
            selection.settings?.let {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
                ) {
                    Text(
                        stringResource(Res.string.settings_retained, it.name),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
        if (state.loading && ready) LinearProgressIndicator(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 76.dp)
                .width(160.dp)
        )
        state.error?.takeIf { ready }?.let { message ->
            Surface(
                Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                    .padding(top = 80.dp, start = margin, end = margin).widthIn(max = 360.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                ),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = model::retry) { Text(stringResource(Res.string.retry)) }
                        state.selected?.style?.endpoint?.credential?.let { key ->
                            TextButton(onClick = {
                                onCredentials(
                                    key.key
                                )
                            }) { Text(stringResource(Res.string.api_key)) }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(8.dp)
                .fillMaxWidth(0.72f)
        ) {
            state.selected?.provider?.attributionFor(state.selected!!.style)
                ?.filterNot { it.requiresLogo }?.forEach { credit ->
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f)
                    ) {
                        Text(
                            credit.text, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable {
                                try {
                                    uri.openUri(credit.url)
                                } catch (_: Exception) {
                                    model.linkFailed()
                                }
                            }.padding(4.dp)
                        )
                    }
                }
            if (state.linkError) Text(
                stringResource(Res.string.link_unavailable),
                color = MaterialTheme.colorScheme.error
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        if (provider == "yandex") Image(
            painterResource(Res.drawable.yandex_logo), stringResource(Res.string.yandex_maps),
            Modifier.align(Alignment.BottomEnd).safeDrawingPadding().width(100.dp).clickable {
                try {
                    uri.openUri("https://yandex.com/maps/")
                } catch (_: Exception) {
                    model.linkFailed()
                }
            })
    }
}

@Composable
private fun MapButton(
    label: String,
    onClick: () -> Unit,
    description: String = label,
    enabled: Boolean = true,
    confirmation: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        border = if (confirmation) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (confirmation) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer.copy(
                alpha = 0.82f
            ),
            contentColor = if (confirmation) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(label) }
}
