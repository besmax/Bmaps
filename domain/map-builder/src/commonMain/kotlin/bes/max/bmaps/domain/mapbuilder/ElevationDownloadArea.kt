/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.WebMercator
import bes.max.bmaps.core.storage.DemMetadata
import bes.max.bmaps.domain.providers.ElevationDataset

data class ElevationDownloadArea(val tileBounds: BoundingBox, val requestBounds: BoundingBox) {
    companion object {
        fun calculate(bounds: BoundingBox, levels: Set<Int>, dataset: ElevationDataset): ElevationDownloadArea? {
            if (dataset == ElevationDataset.NONE || levels.isEmpty() || levels.any { it !in 0..52 }) return null
            val tiles = WebMercator.coveringTileBounds(bounds, levels.min()) ?: return null
            return ElevationDownloadArea(tiles, dataset.paddedBounds(tiles))
        }
    }
}

fun ElevationDataset.supportsTileRequest(bounds: BoundingBox, levels: Set<Int>): Boolean =
    this == ElevationDataset.NONE || ElevationDownloadArea.calculate(bounds, levels, this)?.let { supportsRequest(it.requestBounds) } == true

fun ElevationDataset.estimatedTileBytes(bounds: BoundingBox, levels: Set<Int>): Long? =
    if (this == ElevationDataset.NONE) 0L else ElevationDownloadArea.calculate(bounds, levels, this)?.let { estimatedBytes(it.requestBounds) }

internal fun BuildRequest.elevationDownloadArea(): ElevationDownloadArea? = ElevationDownloadArea.calculate(bounds,
    layers.flatMap { it.zoomLevels.ifEmpty { (it.zoomRange.min..it.zoomRange.max).toSet() } }.toSet(), elevationDataset)

internal fun DemMetadata.coversTileBounds(bounds: BoundingBox): Boolean {
    val west = originLongitude - if (pixelIsPoint) longitudeStep / 2 else 0.0
    val north = originLatitude - if (pixelIsPoint) latitudeStep / 2 else 0.0
    val east = west + width * longitudeStep
    val south = north + height * latitudeStep
    val tolerance = 1e-10
    return west <= bounds.west + tolerance && east >= bounds.east - tolerance &&
        south <= bounds.south + tolerance && north >= bounds.north - tolerance
}
