/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
    val displayCoordinate = state.displayCoordinate
    val coordinates = if (displayCoordinate != null && state.preferences != null)
        formatCoordinate(displayCoordinate, state.preferences.coordinateFormat) else null
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
                    state.coordinateError != null -> stringResource(state.coordinateError)
                    state.preferenceError -> stringResource(Res.string.position_preferences_unavailable)
                    coordinates == null -> stringResource(Res.string.position_coordinates_unsupported)
                    else -> stringResource(Res.string.position_latitude_longitude, coordinates)
                }, style = MaterialTheme.typography.labelMedium
            )
            state.coordinateOperation?.takeIf { it.accuracyMeters > 0 }?.let {
                Text(stringResource(Res.string.position_transform_accuracy,
                    if (it.accuracyMeters < 0.01) "<0.01" else fixedDecimal(it.accuracyMeters, 1)),
                    style = MaterialTheme.typography.labelSmall)
            }
            state.coordinateOperation?.coordinateEpoch?.let {
                Text(stringResource(Res.string.position_coordinate_epoch, fixedDecimal(it, 1)),
                    style = MaterialTheme.typography.labelSmall)
            }
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
