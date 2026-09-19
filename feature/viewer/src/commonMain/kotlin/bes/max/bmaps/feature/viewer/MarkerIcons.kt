@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package bes.max.bmaps.feature.viewer

import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.StringResource

internal data class MarkerIconDefinition(val id: String, val file: String, val label: StringResource)

internal object MarkerIcons {
    val entries = listOf(MarkerIconDefinition("place", "files/markers/place.svg", Res.string.annotations_place))
    private val vectors = mutableMapOf<String, ImageVector>()
    private val mutex = Mutex()

    suspend fun vector(id: String): ImageVector = mutex.withLock {
        val definition = entries.firstOrNull { it.id == id } ?: entries.first()
        vectors.getOrPut(definition.id) {
            parseMarkerSvg(Res.readBytes(definition.file).decodeToString(), definition.id)
        }
    }
}

internal fun parseMarkerSvg(svg: String, name: String): ImageVector {
    val viewBox = Regex("""viewBox="0 0 ([0-9.]+) ([0-9.]+)"""").find(svg) ?: error("Missing SVG viewBox")
    val width = viewBox.groupValues[1].toFloat()
    val height = viewBox.groupValues[2].toFloat()
    val paths = Regex("""<path\s+d="([^"]+)"\s*/>""").findAll(svg).toList()
    require(width > 0 && height > 0 && paths.isNotEmpty())
    return ImageVector.Builder(name, width.dp, height.dp, width, height).apply {
        paths.forEach { addPath(PathParser().parsePathString(it.groupValues[1]).toNodes(), fill = SolidColor(Color.Black)) }
    }.build()
}

@Composable
internal fun MarkerIcon(id: String, color: Color, description: String, modifier: Modifier = Modifier) {
    val vector by produceState<ImageVector?>(null, id) { value = MarkerIcons.vector(id) }
    vector?.let { Icon(it, description, modifier, tint = color) }
}
