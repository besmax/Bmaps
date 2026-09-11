package bes.max.bmaps.feature.constructor

import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.stringResource
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
    Box(Modifier.fillMaxSize().testTag("full-screen-map")) {
        if (ready) {
            RasterMap(model.renderer, Modifier.fillMaxSize().testTag(if (showFixture) "sample-map" else "online-map"))
        }
        if (selection.selecting) SelectionFrame(selection.frame, area::drag)
        Row(
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapButton(stringResource(Res.string.back), onBack)
            if (selection.selecting) MapButton(stringResource(Res.string.cancel_selection), area::cancel)
        }
        Column(
            Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MapButton(stringResource(Res.string.zoom_in_symbol), controls::zoomIn, stringResource(Res.string.zoom_in))
            MapButton(stringResource(Res.string.zoom_out_symbol), controls::zoomOut, stringResource(Res.string.zoom_out))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selection.selecting) {
                MapButton(stringResource(Res.string.accept), area::accept, enabled = selection.bounds != null)
            } else MapButton(stringResource(Res.string.choose_area), area::choose)
            selection.settings?.let {
                Text(
                    stringResource(Res.string.settings_retained, it.name),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (state.loading && ready) LinearProgressIndicator(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 76.dp)
        )
        state.error?.takeIf { ready }?.let { message ->
            Surface(
                Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                    .padding(top = 80.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
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
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
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
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(
                alpha = 0.8f
            )
        )
    ) { Text(label) }
}

