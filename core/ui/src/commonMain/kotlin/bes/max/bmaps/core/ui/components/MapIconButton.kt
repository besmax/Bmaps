package bes.max.bmaps.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun MapIconButton(
    onClick: () -> Unit,
    iconResId: DrawableResource,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors().copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Icon(painterResource(iconResId), contentDescription, modifier = Modifier.size(20.dp))
    }
}
