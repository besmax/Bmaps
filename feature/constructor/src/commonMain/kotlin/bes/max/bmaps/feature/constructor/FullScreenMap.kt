package bes.max.bmaps.feature.constructor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.mapengine.*
import bmaps.feature.constructor.generated.resources.Res
import bmaps.feature.constructor.generated.resources.yandex_logo
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.painterResource

@Composable
fun FullScreenMap(provider: String, style: String, showFixture: Boolean, onBack: () -> Unit,
                  onSettings: () -> Unit, onCredentials: (String) -> Unit) {
    val model = metroViewModel<OnlineMapViewModel>()
    val area = metroViewModel<AreaSelectionViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val selection by area.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val controls = remember { RasterMapController() }
    val fixtureLayers = remember { listOf(RasterLayer("fixture", TileSourceFactory { openFixtureTileSource() })) }
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val settings by rememberUpdatedState(onSettings)
    val ready = state.selected?.id?.let { it.provider.value == provider && it.style.value == style } == true
    LaunchedEffect(provider, style, state.choices) {
        state.choices.find { it.provider.id.value == provider && it.style.id.value == style }?.let {
            if (!ready) model.select(it)
        }
    }
    LaunchedEffect(area, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { area.events.collect { settings() } }
    }
    DisposableEffect(lifecycleState) { onDispose { model.retainViewport() } }
    LaunchedEffect(state.visibleWindow) { area.updateWindow(state.visibleWindow) }
    Box(Modifier.fillMaxSize().testTag("full-screen-map")) {
        if (ready && lifecycleState.isAtLeast(Lifecycle.State.STARTED)) state.session?.let { session ->
            RasterMap(session.config, if (showFixture) fixtureLayers else session.layers,
                Modifier.fillMaxSize().testTag(if (showFixture) "sample-map" else "online-map"), controls) {
                model.onEvent(session.generation, it)
            }
        }
        if (selection.selecting) SelectionFrame()
        Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapButton("Back", onBack)
            if (selection.selecting) MapButton("Cancel selection", area::cancel)
        }
        Column(Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MapButton("+", controls::zoomIn, "Zoom in")
            MapButton("−", controls::zoomOut, "Zoom out")
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (selection.selecting) {
                MapButton("Accept", area::accept, enabled = selection.bounds != null)
            } else MapButton("Choose area", area::choose)
            selection.settings?.let { Text("Settings retained: ${it.name}", style = MaterialTheme.typography.labelSmall) }
        }
        if (state.loading && ready) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 76.dp))
        state.error?.takeIf { ready }?.let { message ->
            Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 80.dp, start = 16.dp, end = 16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = model::retry) { Text("Retry") }
                        state.selected?.style?.endpoint?.credential?.let { key -> TextButton(onClick = { onCredentials(key.key) }) { Text("API key") } }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(8.dp).fillMaxWidth(0.72f)) {
            state.selected?.provider?.attributionFor(state.selected!!.style)?.filterNot { it.requiresLogo }?.forEach { credit ->
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
                    Text(credit.text, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable {
                            try { uri.openUri(credit.url) } catch (_: Exception) { linkError = true }
                        }.padding(4.dp))
                }
            }
            if (linkError) Text("Link unavailable", color = MaterialTheme.colorScheme.error)
        }
        if (provider == "yandex") Image(painterResource(Res.drawable.yandex_logo), "Yandex Maps",
            Modifier.align(Alignment.BottomEnd).safeDrawingPadding().width(100.dp).clickable {
                try { uri.openUri("https://yandex.com/maps/") } catch (_: Exception) { linkError = true }
            })
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit, description: String = label, enabled: Boolean = true) {
    FilledTonalButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))) { Text(label) }
}

@Composable
private fun SelectionFrame() {
    Canvas(Modifier.fillMaxSize()) {
        val left = size.width * 0.12f; val right = size.width * 0.88f
        val top = size.height * 0.25f; val bottom = size.height * 0.75f
        val shade = Color.Black.copy(alpha = 0.25f)
        drawRect(shade, size = Size(size.width, top))
        drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(shade, Offset(0f, top), Size(left, bottom - top))
        drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(3.dp.toPx()))
    }
}
