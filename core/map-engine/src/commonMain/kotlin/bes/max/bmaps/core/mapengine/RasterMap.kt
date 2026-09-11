package bes.max.bmaps.core.mapengine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.plrapps.mapcompose.ui.MapUI

@Composable
fun RasterMap(renderer: RasterMapRenderer, modifier: Modifier = Modifier) {
    val state by renderer.state.collectAsStateWithLifecycle()
    Box(modifier.onSizeChanged(renderer::resize)) {
        state.engine?.let { MapUI(Modifier.fillMaxSize(), it) }
    }
}
