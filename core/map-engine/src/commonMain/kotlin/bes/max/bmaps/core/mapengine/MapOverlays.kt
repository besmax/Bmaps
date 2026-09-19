package bes.max.bmaps.core.mapengine

import androidx.compose.ui.graphics.Color

data class MapMarker(val id: String, val position: MapPoint, val icon: String, val color: Color, val label: String)
data class MapPath(val id: String, val points: List<MapPoint>, val color: Color, val filled: Boolean = false)
