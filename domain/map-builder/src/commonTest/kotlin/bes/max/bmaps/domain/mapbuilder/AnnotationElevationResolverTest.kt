/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AnnotationElevationResolverTest {
    private val id = PackageId("height-test")
    private val point = GeographicCoordinate(1.0, 1.0)
    private val marker = Annotation("marker", AnnotationKind.MARKER, listOf(point))
    private val manifest = PackageManifest(1, id, "Height", BoundingBox(0.0, 0.0, 2.0, 2.0), ZoomRange(0, 0), 0, 0,
        listOf(PackageLayer(LayerId("base"), "Base", null, PackageAsset("map_data.mbtiles", 1),
            BoundingBox(0.0, 0.0, 2.0, 2.0), ZoomRange(0, 0))), elevation = PackageAsset("elevation.tif", 1))

    @Test fun samplesBeforeSavePreservesExistingHeightsAndSharesDuplicatePoints() = runTest {
        var reads = 0
        var closed = false
        val session = session(onClose = { closed = true }) { _, _ ->
            reads++
            PackageElevation.Value(-7.5, "EGM2008")
        }
        val existing = marker.copy(id = "existing", elevations = listOf(AnnotationElevation(0.0, "EGM96")))
        val values = PackageAnnotationElevationResolver(repository(session)).resolve(id,
            listOf(marker, marker.copy(id = "duplicate"), existing))
        assertEquals(1, reads)
        assertTrue(closed)
        assertEquals(AnnotationElevation(-7.5, "EGM2008"), values[0].elevations.single())
        assertEquals(values[0].elevations, values[1].elevations)
        assertEquals(existing, values[2])
    }

    @Test fun absentOrUnavailableSamplesRemainNull() = runTest {
        for (result in listOf(PackageElevation.Missing, PackageElevation.NoData, PackageElevation.OutsideCoverage,
            PackageElevation.Unsupported, PackageElevation.Unavailable, PackageElevation.Value(Double.NaN, "EGM96"))) {
            val values = PackageAnnotationElevationResolver(repository(session { _, _ -> result })).resolve(id, listOf(marker))
            assertNull(values.single().elevations.getOrNull(0))
        }
    }

    @Test fun cancellationClosesReaderAndDoesNotSaveAnIncompleteResolution() = runTest {
        val reading = CompletableDeferred<Unit>()
        var closed = false
        val resolver = PackageAnnotationElevationResolver(repository(session(onClose = { closed = true }) { _, _ ->
            reading.complete(Unit)
            awaitCancellation()
        }))
        var completed = false
        val job = launch { resolver.resolve(id, listOf(marker)); completed = true }
        reading.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertFalse(completed)
    }

    private fun session(onClose: () -> Unit = {}, sample: suspend (Double, Double) -> PackageElevation) = object : OpenedPackage {
        override val manifest = this@AnnotationElevationResolverTest.manifest
        override suspend fun openTiles(layerId: LayerId): PackageResult<TileSource> = error("Not used")
        override suspend fun elevation(latitude: Double, longitude: Double) = sample(latitude, longitude)
        override suspend fun close() { onClose() }
    }

    private fun repository(session: OpenedPackage) = object : PackageRepository {
        override fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>> = error("Not used")
        override fun observe(id: PackageId): Flow<PackageResult<PackageSummary>> = error("Not used")
        override suspend fun open(id: PackageId): PackageResult<OpenedPackage> = PackageResult.Success(session)
        override suspend fun delete(id: PackageId): PackageResult<Unit> = error("Not used")
        override suspend fun setLayerPresentation(id: PackageId, layers: List<LayerPresentation>): PackageResult<Unit> = error("Not used")
        override suspend fun setFavourite(id: PackageId, favourite: Boolean): PackageResult<Unit> = error("Not used")
        override suspend fun setAvatar(id: PackageId, avatar: MapAvatar): PackageResult<Unit> = error("Not used")
    }
}
