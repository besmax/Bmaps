/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class MarkerCubic(val control1: Offset, val control2: Offset, val end: Offset)
private data class MarkerContour(val start: Offset, val curves: List<MarkerCubic>)
private data class MarkerMorph(val anchor: Offset, val contours: List<MarkerContour>)

private val CircleContour = MarkerContour(
    Offset(0f, -1f),
    listOf(
        MarkerCubic(Offset(-0.5522848f, -1f), Offset(-1f, -0.5522848f), Offset(-1f, 0f)),
        MarkerCubic(Offset(-1f, 0.5522848f), Offset(-0.5522848f, 1f), Offset(0f, 1f)),
        MarkerCubic(Offset(0.5522848f, 1f), Offset(1f, 0.5522848f), Offset(1f, 0f)),
        MarkerCubic(Offset(1f, -0.5522848f), Offset(0.5522848f, -1f), Offset(0f, -1f)),
    ),
)

@Composable
internal fun MovingMarkerIcon(
    id: String,
    color: Color,
    moving: Boolean,
    modifier: Modifier = Modifier,
) {
    val asset by produceState<MarkerIconAsset?>(null, id) { value = MarkerIcons.asset(id) }
    val morph = remember(asset) { asset?.let(::prepareMarkerMorph) }
    val progress = animateFloatAsState(
        targetValue = if (moving) 1f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "markerMoveMorph",
    )
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(moving) {
        if (moving) rotation.animateTo(
            targetValue = rotation.value + 360f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        )
    }
    val path = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    val loaded = asset ?: return
    val painter = rememberVectorPainter(loaded.vector)
    Canvas(modifier) {
        val amount = progress.value
        val angle = rotation.value * (PI / 180).toFloat()
        val split = ((amount - 0.65f) / 0.35f).coerceIn(0f, 1f)
        val anchor = morph?.anchor ?: Offset(loaded.vector.viewportWidth / 2, loaded.vector.viewportHeight)
        val iconSize = min(size.width, size.height) * 0.75f
        val iconScale = iconSize / maxOf(loaded.vector.viewportWidth, loaded.vector.viewportHeight)
        withTransform({
            translate(center.x - anchor.x * iconScale, center.y - anchor.y * iconScale)
            scale(iconScale, iconScale, pivot = Offset.Zero)
        }) {
            if (morph != null) {
                val body = (amount / 0.65f).coerceIn(0f, 1f)
                val circleCenter = anchor + orbit(angle) * split
                path.rewind()
                appendMorph(path, morph.contours[0], circleCenter, 4.8f - 0.9f * split, body)
                appendMorph(path, morph.contours[1], circleCenter, 0f, body)
                drawPath(path, color, alpha = 1f - 0.3f * split)
            } else {
                with(painter) {
                    draw(
                        Size(loaded.vector.viewportWidth, loaded.vector.viewportHeight),
                        alpha = 1f - amount,
                        colorFilter = ColorFilter.tint(color),
                    )
                }
                drawCircle(color, radius = 3.9f * amount, center = anchor + orbit(angle) * amount, alpha = 0.7f * amount)
            }
            val spread = if (morph != null) split else amount
            if (spread > 0f) {
                drawCircle(color, radius = 4f * spread, center = anchor + orbit(angle + (2 * PI / 3).toFloat()) * spread, alpha = 0.45f * spread)
                drawCircle(color, radius = 4.8f * spread, center = anchor + orbit(angle + (4 * PI / 3).toFloat()) * spread, alpha = 0.3f * spread)
                drawCircle(color, radius = 0.65f, center = anchor, alpha = spread)
            }
        }
    }
}

private fun orbit(angle: Float) = Offset(cos(angle) * 2.6f, sin(angle) * 2.6f)

private fun appendMorph(path: Path, contour: MarkerContour, center: Offset, radius: Float, progress: Float) {
    val start = lerp(contour.start, center + CircleContour.start * radius, progress)
    path.moveTo(start.x, start.y)
    contour.curves.forEachIndexed { index, curve ->
        val circle = CircleContour.curves[index]
        val control1 = lerp(curve.control1, center + circle.control1 * radius, progress)
        val control2 = lerp(curve.control2, center + circle.control2 * radius, progress)
        val end = lerp(curve.end, center + circle.end * radius, progress)
        path.cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
    }
    path.close()
}

private fun prepareMarkerMorph(asset: MarkerIconAsset): MarkerMorph? {
    if (asset.vector.name != "place" || asset.paths.size != 1) return null
    val contours = cubicContours(asset.paths.single()) ?: return null
    if (contours.size != 2 || contours.any { it.curves.size != CircleContour.curves.size }) return null
    val anchor = contours.first().curves.maxBy { it.end.y }.end
    return MarkerMorph(anchor, contours)
}

private fun cubicContours(nodes: List<PathNode>): List<MarkerContour>? {
    val contours = mutableListOf<MarkerContour>()
    var position = Offset.Zero
    var start: Offset? = null
    var previousControl: Offset? = null
    val curves = mutableListOf<MarkerCubic>()
    for (node in nodes) {
        val curve = when (node) {
            is PathNode.MoveTo, is PathNode.RelativeMoveTo -> {
                if (start != null) return null
                position = when (node) {
                    is PathNode.MoveTo -> Offset(node.x, node.y)
                    is PathNode.RelativeMoveTo -> position + Offset(node.dx, node.dy)
                    else -> return null
                }
                start = position
                previousControl = null
                continue
            }
            is PathNode.CurveTo -> MarkerCubic(Offset(node.x1, node.y1), Offset(node.x2, node.y2), Offset(node.x3, node.y3))
            is PathNode.RelativeCurveTo -> MarkerCubic(position + Offset(node.dx1, node.dy1), position + Offset(node.dx2, node.dy2), position + Offset(node.dx3, node.dy3))
            is PathNode.ReflectiveCurveTo -> MarkerCubic(position * 2f - (previousControl ?: position), Offset(node.x1, node.y1), Offset(node.x2, node.y2))
            is PathNode.RelativeReflectiveCurveTo -> MarkerCubic(position * 2f - (previousControl ?: position), position + Offset(node.dx1, node.dy1), position + Offset(node.dx2, node.dy2))
            PathNode.Close -> {
                val contourStart = start ?: return null
                if (curves.isEmpty() || (position - contourStart).getDistance() > 0.001f) return null
                contours += MarkerContour(contourStart, curves.toList())
                position = contourStart
                start = null
                curves.clear()
                previousControl = null
                continue
            }
            else -> return null
        }
        if (start == null) return null
        curves += curve
        position = curve.end
        previousControl = curve.control2
    }
    return contours.takeIf { start == null && it.isNotEmpty() }
}
