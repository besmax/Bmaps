/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.core.storage.DemMetadata
import bes.max.bmaps.domain.providers.ElevationDataset
import kotlin.test.*

class ElevationDownloadAreaTest {
    private val selection = BoundingBox(10.01, 45.01, 10.09, 45.09)

    @Test fun areaCoversAllSelectedTilesAndUsesTheCoarsestSelectedLevel() {
        val area = assertNotNull(ElevationDownloadArea.calculate(selection, setOf(8, 10), ElevationDataset.COP30))
        assertEquals(WebMercator.coveringTileBounds(selection, 8), area.tileBounds)
        for (key in PackageTileCoverage(selection, ZoomRange(8, 10), setOf(8, 10)).tiles()) {
            val bounds = assertNotNull(WebMercator.tileBounds(key))
            assertTrue(area.tileBounds.west <= bounds.west && area.tileBounds.east >= bounds.east)
            assertTrue(area.tileBounds.south <= bounds.south && area.tileBounds.north >= bounds.north)
        }
        assertTrue(area.requestBounds.west < area.tileBounds.west && area.requestBounds.east > area.tileBounds.east)
        assertTrue(area.requestBounds.south < area.tileBounds.south && area.requestBounds.north > area.tileBounds.north)
        assertEquals(ElevationDataset.COP30.estimatedBytes(area.requestBounds), ElevationDataset.COP30.estimatedTileBytes(selection, setOf(8, 10)))
    }

    @Test fun paddingIsDatasetSpecificAndClampedAtWorldEdges() {
        val tile = BoundingBox(-10.0, -10.0, 10.0, 10.0)
        for (dataset in ElevationDataset.entries.filter { it != ElevationDataset.NONE }) {
            val step = dataset.arcSeconds / 3600
            val padded = dataset.paddedBounds(tile)
            assertTrue(tile.west - padded.west >= 2 * step - 1e-12)
            assertTrue(padded.east - tile.east >= 2 * step - 1e-12)
            assertTrue(tile.south - padded.south >= 2 * step - 1e-12)
            assertTrue(padded.north - tile.north >= 2 * step - 1e-12)
            val world = dataset.paddedBounds(BoundingBox(-180.0, -90.0, 180.0, 90.0))
            assertEquals(BoundingBox(-180.0, -90.0, 180.0, 90.0), world)
        }
    }

    @Test fun limitsUseTileCoverageAndTileOnlyRequestsRemainAvailable() {
        assertTrue(ElevationDataset.COP30.supportsRequest(selection))
        assertFalse(ElevationDataset.COP30.supportsTileRequest(selection, setOf(0, 8)))
        assertTrue(ElevationDataset.COP30.supportsTileRequest(selection, setOf(8)))
        val crossing = BoundingBox(179.0, 0.0, -179.0, 1.0)
        assertNull(ElevationDownloadArea.calculate(crossing, setOf(8), ElevationDataset.COP30))
        assertTrue(ElevationDataset.NONE.supportsTileRequest(crossing, setOf(8)))
        assertEquals(0L, ElevationDataset.NONE.estimatedTileBytes(crossing, setOf(8)))
        assertNull(ElevationDownloadArea.calculate(selection, emptySet(), ElevationDataset.COP30))
        assertNull(ElevationDownloadArea.calculate(selection, setOf(53), ElevationDataset.COP30))
    }

    @Test fun coverageVerificationUsesAreaAndPointFootprints() {
        val area = DemMetadata(4, 4, 10.0, 50.0, 0.25, -0.25, false, 16, 2, 1, false, 32)
        assertTrue(area.coversTileBounds(BoundingBox(10.0, 49.0, 11.0, 50.0)))
        assertFalse(area.coversTileBounds(BoundingBox(9.999, 49.0, 11.0, 50.0)))
        assertFalse(area.coversTileBounds(BoundingBox(10.0, 48.999, 11.0, 50.0)))
        val point = area.copy(pixelIsPoint = true)
        assertTrue(point.coversTileBounds(BoundingBox(9.875, 49.125, 10.875, 50.125)))
        assertFalse(point.coversTileBounds(BoundingBox(9.875, 49.0, 11.0, 50.125)))
    }
}
