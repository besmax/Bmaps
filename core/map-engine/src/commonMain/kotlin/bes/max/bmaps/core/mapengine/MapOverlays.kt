/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import androidx.compose.ui.graphics.Color

enum class MapMarkerAnchor { BOTTOM_CENTER, CENTER }

data class MapMarker(
    val id: String,
    val position: MapPoint,
    val icon: String,
    val color: Color,
    val label: String,
    val anchor: MapMarkerAnchor = MapMarkerAnchor.BOTTOM_CENTER,
    val zIndex: Float = 0f,
)
data class MapPath(val id: String, val points: List<MapPoint>, val color: Color, val filled: Boolean = false, val zIndex: Float = 0f)
