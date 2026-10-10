/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.mapengine.*
import bes.max.bmaps.domain.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.*

class SampledDownloadEstimatorTest {
    private val bounds = BoundingBox(-180.0, -WebMercator.MAX_LATITUDE, 180.0, WebMercator.MAX_LATITUDE)
    private val layer = BuildLayerRequest(LayerId("base"), ProviderStyleId(ProviderId("fixture"), StyleId("map")),
        ProviderConfig(levelLimits = LevelLimitsConfig(0, 4)), ZoomRange(0, 2))
    private val request = BuildRequest(PackageId("sample"), "Sample", bounds, listOf(layer))

    @Test fun samplesAreBoundedDistinctAndInsideCoverageIncludingDateline() {
        for (area in listOf(bounds, BoundingBox(170.0, -10.0, -170.0, 10.0))) {
            val coverage = PackageTileCoverage(area, ZoomRange(4, 4), setOf(4))
            val keys = coverage.sampleTiles()
            assertTrue(keys.size <= 18)
            assertEquals(keys.distinct(), keys)
            assertTrue(keys.all(coverage::contains))
        }
    }

    @Test fun refinesPerZoomIncludesHiddenLayersAndReusesMeasurements() = runTest {
        var reads = 0
        var closes = 0
        val estimator = SampledDownloadEstimator(DownloadSourceOpener { selected ->
            OnlineSourceResult.Available(object : TileSource {
                override suspend fun read(key: TileKey): TileReadResult {
                    reads++
                    val size = if (selected.id == layer.id) (key.level + 1) * 1000 else 5000
                    return TileReadResult.Available(ByteString(ByteArray(size)), RasterTileFormat.PNG)
                }
                override suspend fun close() { closes++ }
            })
        })
        val selected = request.copy(layers = listOf(layer, layer.copy(id = LayerId("hidden"), visible = false,
            source = ProviderStyleId(ProviderId("fixture"), StyleId("other")))))
        val results = estimator.estimates(selected).toList()
        assertEquals(42, results.last().tileCount)
        assertTrue(results.last().estimatedPackageBytes!! < results.first().estimatedPackageBytes!!)
        assertTrue(reads <= 38)
        assertEquals(2, closes)
        val firstReads = reads
        assertEquals(results.last(), estimator.estimates(selected).last())
        assertEquals(firstReads, reads)
        assertEquals(4, closes)
        val base = 65_536L + 1 * (1080 + 128) + 4 * (2160 + 128) + 16 * (3240 + 128)
        val hidden = 65_536L + 21 * (5400 + 128)
        assertEquals(base + hidden, results.last().estimatedPackageBytes)
    }

    @Test fun unavailableOrMissingTilesKeepFallbackAndCloseSources() = runTest {
        var closed = false
        val unavailable = SampledDownloadEstimator(DownloadSourceOpener {
            OnlineSourceResult.Failed(OnlineSourceFailure.MISSING_CREDENTIAL)
        }).estimates(request).toList()
        assertEquals(1, unavailable.size)
        val missing = SampledDownloadEstimator(DownloadSourceOpener {
            OnlineSourceResult.Available(object : TileSource {
                override suspend fun read(key: TileKey) = TileReadResult.Missing
                override suspend fun close() { closed = true }
            })
        }).estimates(request).toList()
        assertEquals(unavailable, missing)
        assertTrue(closed)
    }

    @Test fun cancellationClosesSourceAndDoesNotBecomeFallbackSuccess() = runTest {
        val reading = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val estimator = SampledDownloadEstimator(DownloadSourceOpener {
            OnlineSourceResult.Available(object : TileSource {
                override suspend fun read(key: TileKey): TileReadResult {
                    reading.complete(Unit)
                    awaitCancellation()
                }
                override suspend fun close() { closed.complete(Unit) }
            })
        })
        val job = launch { estimator.estimates(request).collect() }
        reading.await()
        job.cancelAndJoin()
        assertTrue(closed.isCompleted)
    }
}
