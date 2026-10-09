/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.constructor.map.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bes.max.bmaps.core.mapengine.RasterMap
import bes.max.bmaps.feature.constructor.map.presentation.FixtureViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

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
