package bes.max.bmaps.feature.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import bes.max.bmaps.domain.mapbuilder.PackageElevation
import bmaps.feature.viewer.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapCrosshair(modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp)) {
        val inset = 4.dp.toPx()
        listOf(Color.White to 3.dp.toPx(), Color.Black to 1.dp.toPx()).forEach { (color, width) ->
            drawLine(
                color,
                Offset(inset, center.y),
                Offset(size.width - inset, center.y),
                width,
                StrokeCap.Square
            )
            drawLine(
                color,
                Offset(center.x, inset),
                Offset(center.x, size.height - inset),
                width,
                StrokeCap.Square
            )
        }
    }
}

@Composable
internal fun MapPositionOverlay(state: MapPositionState, modifier: Modifier = Modifier) {
    val point = state.coordinate ?: return
    val coordinates = state.preferences?.let { formatCoordinate(point, it) }
    Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f), tonalElevation = 3.dp
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                when {
                    state.preferenceError -> stringResource(Res.string.position_preferences_unavailable)
                    coordinates == null -> stringResource(Res.string.position_coordinates_unsupported)
                    state.preferences?.defaultCoordinateSystem == "EPSG:3857" -> coordinates
                    else -> stringResource(Res.string.position_latitude_longitude, coordinates)
                }, style = MaterialTheme.typography.labelMedium
            )
            val elevation = state.elevation
            val label = when {
                state.error != null -> stringResource(state.error)
                state.sampling -> stringResource(Res.string.position_elevation_loading)
                elevation is PackageElevation.Value -> stringResource(
                    Res.string.position_elevation_value,
                    fixedDecimal(elevation.meters, 1), elevation.verticalReference
                )

                elevation == PackageElevation.NoData -> stringResource(Res.string.position_elevation_no_data)
                elevation == PackageElevation.OutsideCoverage -> stringResource(Res.string.position_elevation_outside)
                elevation == PackageElevation.Unsupported -> stringResource(Res.string.position_elevation_unsupported)
                elevation == PackageElevation.Unavailable -> stringResource(Res.string.position_elevation_unavailable)
                else -> null
            }
            if (label != null) Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
