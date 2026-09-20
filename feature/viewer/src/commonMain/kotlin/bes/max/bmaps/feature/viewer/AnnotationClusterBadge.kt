package bes.max.bmaps.feature.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AnnotationClusterBadge(count: Int, onClick: () -> Unit) {
    val text = count.toString()
    val style = MaterialTheme.typography.labelLarge
    val measured = rememberTextMeasurer().measure(text, style)
    val size = with(LocalDensity.current) { maxOf(48.dp, maxOf(measured.size.width, measured.size.height).toDp() + 24.dp) }
    val description = stringResource(Res.string.annotations_cluster_accessibility, count)
    Surface(
        modifier = Modifier.size(size).clearAndSetSemantics {
            role = Role.Button
            contentDescription = description
            onClick { onClick(); true }
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
        shadowElevation = 2.dp,
    ) { Box(contentAlignment = Alignment.Center) { Text(text, style = style) } }
}
