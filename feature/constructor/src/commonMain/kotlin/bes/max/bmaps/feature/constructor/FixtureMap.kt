package bes.max.bmaps.feature.constructor

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bmaps.feature.constructor.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.ExperimentalResourceApi

data class FixtureState(val error: StringResource? = null)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FixtureViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(FixtureState())
    val state = mutableState.asStateFlow()
    val renderer = RasterMapRenderer()

    init {
        viewModelScope.launch { renderer.run() }
        renderer.setContent(0, RasterMapConfig(TilePyramid(ZoomRange(0, 3))),
            listOf(RasterLayer("sample", TileSourceFactory { openFixtureTileSource() })))
        viewModelScope.launch { renderer.events.collect { onEvent(it.event) } }
    }

    private fun onEvent(event: MapEvent) {
        when (event) {
            is MapEvent.Unavailable -> mutableState.value = FixtureState(Res.string.sample_map_unavailable)
            is MapEvent.TileFailed -> mutableState.value = FixtureState(Res.string.sample_tile_unavailable)
            else -> Unit
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun FixtureMap(modifier: Modifier = Modifier) {
    val model = metroViewModel<FixtureViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    androidx.compose.foundation.layout.Box(modifier) {
        RasterMap(model.renderer, Modifier.matchParentSize())
        state.error?.let { androidx.compose.material3.Text(stringResource(it)) }
    }
}

@OptIn(ExperimentalResourceApi::class)
internal suspend fun openFixtureTileSource(): TileSource {
        val png = ByteString(Res.readBytes("files/fixture.png"))
        val jpeg = ByteString(Res.readBytes("files/fixture.jpg"))
        return object : TileSource {
            private val closed = MutableStateFlow(false)
            override suspend fun read(key: TileKey): TileReadResult = when {
                closed.value -> TileReadResult.Failed(TileReadFailure.CLOSED)
                (key.column + key.row) % 2L == 0L -> TileReadResult.Available(png, RasterTileFormat.PNG)
                else -> TileReadResult.Available(jpeg, RasterTileFormat.JPEG)
            }
            override suspend fun close() { closed.value = true }
        }
}
