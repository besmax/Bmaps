package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
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
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.DrawableResource
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
    BoxWithConstraints(Modifier.fillMaxSize().testTag("full-screen-map")) {
        val margin = if (maxWidth < 600.dp) 16.dp else 24.dp
        if (ready) {
            RasterMap(model.renderer, Modifier.fillMaxSize().testTag(if (showFixture) "sample-map" else "online-map"))
        }
        if (selection.selecting) SelectionFrame(selection.frame, area::drag)
        Row(
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(margin),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapIconButton(
                onClick = onBack,
                iconResId = Res.drawable.ic_arrow_back,
                contentDescription = stringResource(Res.string.back)
            )
            if (selection.selecting) MapButton(stringResource(Res.string.cancel_selection), area::cancel)
        }
        Column(
            Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(margin),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapIconButton(
                onClick = controls::zoomIn,
                iconResId = Res.drawable.ic_zoom_in,
                contentDescription = stringResource(Res.string.zoom_in)
            )

            MapIconButton(
                onClick = controls::zoomOut,
                iconResId = Res.drawable.ic_zoom_out,
                contentDescription = stringResource(Res.string.zoom_out)
            )
        }
        Column(
            Modifier.align(if (maxWidth < 600.dp) Alignment.BottomCenter else Alignment.BottomEnd)
                .safeDrawingPadding().padding(start = margin, end = margin, bottom = 64.dp).widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selection.selecting) {
                MapButton(stringResource(Res.string.accept), area::accept, enabled = selection.bounds != null, confirmation = true)
            } else {
                MapIconButton(
                    onClick = area::choose,
                    iconResId = Res.drawable.ic_crop_area,
                    contentDescription = stringResource(Res.string.choose_area),
                    active = true,
                )
            }
            selection.settings?.let {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)) {
                    Text(stringResource(Res.string.settings_retained, it.name),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        if (state.loading && ready) LinearProgressIndicator(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 76.dp).width(160.dp)
        )
        state.error?.takeIf { ready }?.let { message ->
            Surface(
                Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                    .padding(top = 80.dp, start = margin, end = margin).widthIn(max = 360.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
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
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f)) {
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
            if (state.linkError) Text(stringResource(Res.string.link_unavailable), color = MaterialTheme.colorScheme.error)
        }
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
        border = if (confirmation) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (confirmation) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
            contentColor = if (confirmation) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(label) }
}

@Composable
private fun MapIconButton(
    onClick: () -> Unit,
    iconResId: DrawableResource,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
        contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        shadowElevation = 4.dp,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painterResource(iconResId), contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}
