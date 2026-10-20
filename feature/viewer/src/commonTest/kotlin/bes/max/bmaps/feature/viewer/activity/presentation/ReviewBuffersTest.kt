/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.activity.presentation

import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.domain.activity.*
import bmaps.feature.viewer.generated.resources.*
import kotlin.test.*

class ReviewBuffersTest {
    @Test fun chartStatisticsUseAllSamplesAndDisplayKeepsExtremaWithinItsBudget() {
        val buffer = ReviewSeriesBuffer("speed", Res.string.history_speed, "GPS", "km/h", emptyList())
        repeat(10_000) { buffer.add(sample("s$it", it.toLong()), if (it == 5000) 100.0 else 10.0) }
        val result = buffer.snapshot()
        assertEquals(10_000L, result.count)
        assertEquals("100.0", result.maximum)
        assertEquals("10.0", result.mean)
        assertTrue(result.points.size <= 1200)
        assertTrue(result.points.any { it.value == 100.0 })
        assertEquals("s0", result.points.first().id)
        assertEquals("s9999", result.points.last().id)
    }
    @Test fun pausesClockChangesAndPositionGapsSplitChartRuns() {
        val buffer = ReviewSeriesBuffer("speed", Res.string.history_speed, "GPS", "km/h", listOf(1500))
        buffer.add(sample("one", 1000), 1.0)
        buffer.add(sample("two", 2000), 2.0)
        buffer.add(sample("three", 3000).copy(acquiredAt = ActivityTime(3000, null, "watch")), 3.0)
        buffer.add(sample("four", 30_000), 4.0)
        assertEquals(4, buffer.snapshot().points.map { it.run }.distinct().size)
    }
    @Test fun positionThinningPreservesEverySegmentBoundaryAndAcquisitionIdentity() {
        val buffer = ReviewPositionBuffer()
        repeat(25_000) { index -> buffer.add(sample("p$index", index.toLong()).copy(pathSegmentId = if (index < 12_500) "first" else "second")) }
        val points = buffer.snapshot()
        assertTrue(points.size <= 20_000)
        assertEquals("p0", points.first().id)
        assertEquals("p24999", points.last().id)
        assertTrue(points.any { it.id == "p12499" })
        assertTrue(points.any { it.id == "p12500" })
        assertEquals(12_500L, points.first { it.segment == "second" }.time)
    }
    @Test fun altitudeFormattingKeepsNegativeMeasurements() { assertEquals("−12.5", reviewNumber(-12.5)) }
    private fun sample(id: String, time: Long) = TelemetrySample(id, MeasurementSource("phone", MeasurementSourceKind.PHONE_LOCATION),
        ActivityTime(time, time, "clock"), ActivityTime(time, time, "clock"), MeasurementPhase.RECORDING,
        PositionMeasurement(GeographicCoordinate(50.0, 30.0), 3.0), "segment")
}
