/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.position.ui

import bes.max.bmaps.feature.viewer.position.presentation.MapPositionState
import bes.max.bmaps.feature.viewer.position.presentation.fixedDecimal
import bes.max.bmaps.feature.viewer.position.presentation.formatCoordinate
import bes.max.bmaps.feature.viewer.position.presentation.formatCoordinateAxes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import bes.max.bmaps.domain.mapbuilder.PackageElevation
import bes.max.bmaps.core.mapengine.CoordinateSystemId
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
internal fun MapPositionOverlay(
    state: MapPositionState,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val displayCoordinate = state.displayCoordinate
    val coordinates = if (displayCoordinate != null && state.preferences != null)
        formatCoordinate(displayCoordinate, state.preferences.coordinateFormat) else null
    val coordinateAxes = if (!singleLine && displayCoordinate != null && state.preferences != null &&
        state.coordinateError == null && !state.preferenceError)
        formatCoordinateAxes(displayCoordinate, state.preferences.coordinateFormat) else null
    val coordinateLabel = when {
        state.coordinateError != null -> stringResource(state.coordinateError)
        state.preferenceError -> stringResource(Res.string.position_preferences_unavailable)
        displayCoordinate == null -> stringResource(Res.string.position_coordinates_calculating)
        coordinates == null -> stringResource(Res.string.position_coordinates_unsupported)
        else -> stringResource(Res.string.position_latitude_longitude, coordinates)
    }
    val elevation = state.elevation
    val elevationLabel = when {
        state.error != null -> stringResource(state.error)
        state.sampling -> stringResource(Res.string.position_elevation_loading)
        elevation is PackageElevation.Value -> stringResource(
            Res.string.position_elevation_value, fixedDecimal(elevation.meters, 1)
        )
        elevation == PackageElevation.NoData -> stringResource(Res.string.position_elevation_no_data)
        elevation == PackageElevation.OutsideCoverage -> stringResource(Res.string.position_elevation_outside)
        elevation == PackageElevation.Unsupported -> stringResource(Res.string.position_elevation_unsupported)
        elevation == PackageElevation.Unavailable -> stringResource(Res.string.position_elevation_unavailable)
        else -> null
    }
    Surface(
        modifier.wrapContentWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f), tonalElevation = 3.dp
    ) {
        if (singleLine) {
            Text(
                listOfNotNull(coordinateLabel, elevationLabel).joinToString("  ·  "),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                if (coordinateAxes != null) {
                    val projected = displayCoordinate?.coordinateSystem == CoordinateSystemId.WebMercator
                    Text(stringResource(
                        if (projected) Res.string.position_x else Res.string.position_latitude,
                        coordinateAxes.first,
                    ), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(
                        if (projected) Res.string.position_y else Res.string.position_longitude,
                        coordinateAxes.second,
                    ), style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(coordinateLabel, style = MaterialTheme.typography.labelMedium)
                }
                Text(elevationLabel ?: stringResource(Res.string.position_elevation_missing),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
