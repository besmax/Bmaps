/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import bes.max.bmaps.domain.mapbuilder.Annotation
import bes.max.bmaps.domain.mapbuilder.AnnotationKind
import bes.max.bmaps.core.mapengine.MapCameraSnapshot
import bes.max.bmaps.core.mapengine.MapPoint
import bes.max.bmaps.core.mapengine.RasterMapCallout
import bes.max.bmaps.core.mapengine.RasterMapRenderer
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

private data class CalloutContent(
    val annotation: Annotation,
    val position: MapPoint,
    val anchor: Offset,
    val layout: IntSize,
    val moving: Boolean,
    val error: StringResource?,
)

@Composable
internal fun MapObjectCallout(
    renderer: RasterMapRenderer,
    annotation: Annotation?,
    position: MapPoint?,
    camera: MapCameraSnapshot?,
    animated: Boolean,
    busy: Boolean,
    moving: Boolean,
    error: StringResource?,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val anchor = if (camera != null && position != null) {
        val window = camera.visibleWindow
        val width = window.right - window.left
        val height = window.bottom - window.top
        if (width > 0 && height > 0 && camera.layout.width > 0 && camera.layout.height > 0) Offset(
            (((position.x - window.left) / width) * camera.layout.width).toFloat(),
            (((position.y - window.top) / height) * camera.layout.height).toFloat(),
        ) else null
    } else null
    val current = if (annotation != null && position != null && anchor != null && camera != null) CalloutContent(
        annotation,
        position,
        anchor,
        camera.layout,
        moving,
        error
    ) else null
    var retained by remember(camera?.session) { mutableStateOf<CalloutContent?>(null) }
    SideEffect { if (current != null) retained = current }
    val content = current ?: retained
    var expanded by remember(content?.annotation?.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val visibility = remember(camera?.session) { MutableTransitionState(false) }
    LaunchedEffect(current != null, visibility) { visibility.targetState = current != null }
    val title = content?.annotation?.name?.takeIf { it.isNotBlank() }
        ?: content?.annotation?.let { stringResource(it.kind.label()) }.orEmpty()
    if (content != null && (!visibility.isIdle || visibility.currentState || visibility.targetState)) {
        val point = content.anchor
        val maxWidthPx = content.layout.width
        val maxHeightPx = content.layout.height
        val maxWidth = with(density) { maxWidthPx.toDp() }
        val maxHeight = with(density) { maxHeightPx.toDp() }
        val gapPx = with(density) { 12.dp.roundToPx() }
        val desiredX =
            if (point.x >= maxWidthPx / 2f) point.x - size.width - gapPx else point.x + gapPx
        val desiredY =
            if (point.y >= maxHeightPx / 2f) point.y - size.height - gapPx else point.y + gapPx
        val x = desiredX.coerceIn(0f, (maxWidthPx - size.width).coerceAtLeast(0).toFloat())
        val y = desiredY.coerceIn(0f, (maxHeightPx - size.height).coerceAtLeast(0).toFloat())
        RasterMapCallout(
            renderer = renderer,
            id = "annotation-callout:${content.annotation.id}",
            position = content.position,
            modifier = Modifier.offset { IntOffset((x - point.x).roundToInt(), (y - point.y).roundToInt()) },
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = if (animated) fadeIn() + scaleIn(initialScale = 0.92f) else EnterTransition.None,
                exit = if (animated) fadeOut() + scaleOut(targetScale = 0.96f) else ExitTransition.None,
            ) {
                Surface(
                    Modifier
                        .widthIn(max = maxWidth.coerceAtMost(200.dp))
                        .heightIn(max = maxHeight.coerceAtMost(180.dp))
                        .onSizeChanged { size = it },
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(max = 256.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        content.annotation.description.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                            )
                            if (description.length > 180) {
                                TextButton({ expanded = !expanded }) {
                                    Text(stringResource(if (expanded) Res.string.viewer_show_less else Res.string.viewer_show_more))
                                }
                            }
                        }
                        if (content.moving) {
                            Text(
                                stringResource(Res.string.annotations_move_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        content.error?.let {
                            Text(
                                stringResource(it),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onEdit, enabled = current != null && !busy) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_edit),
                                    contentDescription = stringResource(Res.string.annotations_edit)
                                )
                            }

                            if (content.annotation.kind == AnnotationKind.MARKER) IconButton(onMove, enabled = current != null && !busy && !moving) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_cursor_move),
                                    contentDescription = stringResource(Res.string.annotations_move)
                                )
                            }

                            IconButton(onDelete, enabled = current != null && !busy) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_delete_forever),
                                    contentDescription = stringResource(Res.string.annotations_delete)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
