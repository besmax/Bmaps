/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.feature.constructor.map.fixture.openFixtureTileSource
import bmaps.feature.constructor.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

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
