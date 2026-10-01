/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package bes.max.bmaps.feature.viewer

import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.StringResource

internal data class MarkerIconDefinition(val id: String, val file: String, val label: StringResource)
internal data class MarkerIconAsset(val vector: ImageVector, val paths: List<List<PathNode>>)

internal object MarkerIcons {
    val entries = listOf(MarkerIconDefinition("place", "files/markers/place.svg", Res.string.annotations_place))
    private val assets = mutableMapOf<String, MarkerIconAsset>()
    private val mutex = Mutex()

    suspend fun vector(id: String): ImageVector = asset(id).vector

    suspend fun asset(id: String): MarkerIconAsset = mutex.withLock {
        val definition = entries.firstOrNull { it.id == id } ?: entries.first()
        assets.getOrPut(definition.id) {
            parseMarkerAsset(Res.readBytes(definition.file).decodeToString(), definition.id)
        }
    }
}

internal fun parseMarkerSvg(svg: String, name: String): ImageVector = parseMarkerAsset(svg, name).vector

private fun parseMarkerAsset(svg: String, name: String): MarkerIconAsset {
    val viewBox = Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""").find(svg) ?: error("Missing SVG viewBox")
    val width = viewBox.groupValues[1].toFloat()
    val height = viewBox.groupValues[2].toFloat()
    val paths = Regex("""<path\s+d="([^"]+)"\s*/>""").findAll(svg).toList()
    require(width > 0 && height > 0 && paths.isNotEmpty())
    val nodes = paths.map { PathParser().parsePathString(it.groupValues[1]).toNodes() }
    val vector = ImageVector.Builder(name, width.dp, height.dp, width, height).apply {
        nodes.forEach { addPath(it, fill = SolidColor(Color.Black)) }
    }.build()
    return MarkerIconAsset(vector, nodes)
}

@Composable
internal fun MarkerIcon(id: String, color: Color, description: String, modifier: Modifier = Modifier) {
    val vector by produceState<ImageVector?>(null, id) { value = MarkerIcons.vector(id) }
    vector?.let { Icon(it, description, modifier, tint = color) }
}
