/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import bmaps.feature.library.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryActionFab(
    expanded: Boolean,
    importEnabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onBuild: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { visibility.targetState = expanded }
    val transition = rememberTransition(visibility, label = "libraryFabTree")
    val progress = transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = if (targetState) 480 else 280, easing = FastOutSlowInEasing)
        },
        label = "branchGrowth",
    ) { if (it) 1f else 0f }
    val contentAlpha = transition.animateFloat(
        transitionSpec = {
            if (targetState) tween(durationMillis = 140, delayMillis = 340, easing = LinearOutSlowInEasing)
            else tween(durationMillis = 100, easing = FastOutSlowInEasing)
        },
        label = "actionContentAlpha",
    ) { if (it) 1f else 0f }
    val popupVisible = visibility.currentState || visibility.targetState
    val addLabel = stringResource(Res.string.library_add_map)
    val dismissLabel = stringResource(Res.string.library_close_actions)
    Box(modifier.size(56.dp)) {
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.fillMaxSize().clearAndSetSemantics {
                if (!popupVisible) {
                    contentDescription = addLabel
                    role = Role.Button
                    onClick { onExpandedChange(true); true }
                }
            },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = Color.White,
        ) {
            LibraryPlusIcon(Modifier.size(24.dp).graphicsLayer {
                rotationZ = progress.value.coerceIn(0f, 1f) * 45f
            })
        }
        if (popupVisible) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier.width(240.dp).height(240.dp)
                        .clickable(onClickLabel = dismissLabel) { onExpandedChange(false) },
                ) {
                    LibraryTreeBackground(progress = { progress.value })
                    val buildLabel = stringResource(Res.string.build_a_map)
                    val importLabel = stringResource(Res.string.transfer_import)
                    LibraryFabAction(
                        buildLabel, Res.drawable.avatar_map, expanded, onBuild,
                        Modifier.align(Alignment.TopStart).width(200.dp).graphicsLayer {
                            translationY = 16.dp.toPx()
                            alpha = contentAlpha.value
                        },
                    )
                    LibraryFabAction(
                        importLabel, Res.drawable.ic_import_map, expanded && importEnabled, onImport,
                        Modifier.align(Alignment.TopStart).width(200.dp).graphicsLayer {
                            translationY = 88.dp.toPx()
                            alpha = contentAlpha.value
                        },
                    )
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(56.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = dismissLabel) { onExpandedChange(false) }
                            .semantics { contentDescription = dismissLabel },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryFabAction(
    label: String,
    icon: DrawableResource,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
            style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color.White.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

@Composable
private fun LibraryPlusIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val inset = size.minDimension * 0.2f
        drawLine(Color.White, Offset(inset, center.y), Offset(size.width - inset, center.y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(center.x, inset), Offset(center.x, size.height - inset), 2.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun LibraryTreeBackground(progress: () -> Float) {
    val color = MaterialTheme.colorScheme.primaryContainer
    val branch = remember { Path() }
    val grownBranch = remember { Path() }
    val measure = remember { PathMeasure() }
    val gradient = remember(color) {
        Brush.verticalGradient(listOf(lerp(color, Color.White, 0.16f), color, lerp(color, Color.Black, 0.08f)))
    }
    Canvas(Modifier.fillMaxSize()) {
        val amount = progress().coerceIn(0f, 1f)
        val radius = 28.dp.toPx()
        val root = Offset(size.width - radius, size.height - radius)
        val tipX = size.width - 68.dp.toPx()
        clipRect(bottom = size.height - radius * 2) {
            repeat(2) { index ->
                val tip = Offset(tipX, (44.dp + 72.dp * index).toPx())
                val delay = if (index == 0) 0.1f else 0f
                val growth = ((amount - delay) / 0.65f).coerceIn(0f, 1f)
                val bendX = if (index == 0) size.width - 6.dp.toPx() else tipX - 36.dp.toPx()
                branch.rewind()
                branch.moveTo(root.x, root.y)
                branch.cubicTo(
                    root.x, root.y - 52.dp.toPx(),
                    bendX, tip.y,
                    tip.x, tip.y,
                )
                measure.setPath(branch, forceClosed = false)
                grownBranch.rewind()
                measure.getSegment(0f, measure.length * growth, grownBranch, startWithMoveTo = true)
                drawPath(grownBranch, gradient, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                val bloom = ((amount - 0.55f - delay) / (0.45f - delay)).coerceIn(0f, 1f)
                if (bloom > 0f) {
                    val budRadius = radius * bloom
                    val width = budRadius * 2 + (200.dp.toPx() - radius * 2) * bloom * bloom
                    drawRoundRect(
                        brush = gradient,
                        topLeft = Offset(tip.x + budRadius - width, tip.y - budRadius),
                        size = androidx.compose.ui.geometry.Size(width, budRadius * 2),
                        cornerRadius = CornerRadius(budRadius, budRadius),
                    )
                }
            }
        }
    }
}
