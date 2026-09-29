/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.mapengine

import androidx.compose.ui.unit.IntSize

/** Latest measured camera state. All scales are relative to the renderer fit scale. */
data class MapCameraSnapshot(
    val session: Long,
    val pyramid: TilePyramid,
    val viewport: MapViewport,
    val visibleWindow: MapWindow,
    val layout: IntSize,
    val fitScale: Double,
    val maxScale: Double,
    val minScale: Double = 1.0,
    val zoomEnabled: Boolean = true,
    val generation: Int = 0,
)

internal fun cameraDestination(snapshot: MapCameraSnapshot, viewport: MapViewport): MapViewport? {
    if (!snapshot.zoomEnabled || !viewport.center.isValid || !viewport.scale.isFinite() || viewport.scale <= 0) return null
    return viewport.copy(scale = viewport.scale.coerceIn(snapshot.minScale, snapshot.maxScale))
}
