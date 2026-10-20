/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.exchange

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityRecord
import bes.max.bmaps.domain.activity.model.ActivityResult
import bes.max.bmaps.domain.activity.model.ActivityStatus
import bes.max.bmaps.domain.activity.model.ActivityValidation
import bes.max.bmaps.domain.activity.model.PositionMeasurement
import bes.max.bmaps.domain.activity.navigation.ActivityPositionQuery
import bes.max.bmaps.domain.activity.repository.ActivityEventQuery
import bes.max.bmaps.domain.activity.repository.ActivityRepository
import bes.max.bmaps.domain.activity.repository.ActivityRouteRepository
import bes.max.bmaps.domain.activity.repository.TelemetryCursor
import bes.max.bmaps.domain.activity.repository.TelemetryQuery
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ActivityExportException(val reason: ActivityFailure) : IOException("Activity export failed")

@Inject
@SingleIn(AppScope::class)
class ActivityExporter(private val activities: ActivityRepository, private val routes: ActivityRouteRepository) {
    suspend fun route(id: String, destination: RawSink) {
        val route = routes.get(id).exportValue()
        if (ActivityValidation.route(route) != null) throw ActivityExportException(ActivityFailure.CorruptData)
        val writer = ActivityExportWriter(destination)
        writer.gpxStart(route.name)
        for (segment in route.segments) {
            writer.text("<trkseg>")
            for (vertex in segment.vertices) writer.point(vertex.coordinate.latitude, vertex.coordinate.longitude, vertex.sourceTimeEpochMillis)
            writer.text("</trkseg>")
        }
        writer.text("</trk></gpx>")
        writer.finish()
    }

    suspend fun recordedPath(id: String, destination: RawSink) {
        val record = frozen(id)
        val writer = ActivityExportWriter(destination)
        writer.gpxStart(record.name)
        var cursor = 0L
        var segment: String? = null
        while (true) {
            val page = activities.positions(id, ActivityPositionQuery(cursor)).exportValue()
            for (sample in page.items) {
                val point = sample.measurement as? PositionMeasurement ?: throw ActivityExportException(ActivityFailure.CorruptData)
                if (sample.pathSegmentId != segment) {
                    if (segment != null) writer.text("</trkseg>")
                    writer.text("<trkseg>")
                    segment = sample.pathSegmentId ?: throw ActivityExportException(ActivityFailure.CorruptData)
                }
                writer.point(point.coordinate.latitude, point.coordinate.longitude, sample.acquiredAt.epochMillis)
            }
            val next = page.nextSequence ?: break
            if (next <= cursor) throw ActivityExportException(ActivityFailure.CorruptData)
            cursor = next
        }
        if (segment != null) writer.text("</trkseg>")
        writer.text("</trk></gpx>")
        unchanged(record)
        writer.finish()
    }

    suspend fun telemetry(id: String, destination: RawSink) {
        val record = frozen(id)
        val writer = ActivityExportWriter(destination)
        val json = Json { encodeDefaults = true }
        writer.text("{\"format\":\"bmaps.activity\",\"version\":1,\"activity\":")
        writer.text(json.encodeToString(record))
        writer.text(",\"navigationRoute\":")
        val guidance = activities.navigationRoute(id).exportValue()
        writer.text(if (guidance == null) "null" else json.encodeToString(guidance))
        writer.text(",\"samples\":[")
        var cursor: TelemetryCursor? = null
        var first = true
        while (true) {
            val page = activities.telemetry(id, TelemetryQuery(0, Long.MAX_VALUE, cursor, 500)).exportValue()
            for (sample in page.items) {
                if (!first) writer.text(",")
                writer.text(json.encodeToString(sample))
                first = false
            }
            val next = page.next ?: break
            if (next == cursor) throw ActivityExportException(ActivityFailure.CorruptData)
            cursor = next
        }
        writer.text("],\"events\":[")
        var sequence: Long? = null
        first = true
        while (true) {
            val page = activities.events(id, ActivityEventQuery(sequence, 500)).exportValue()
            for (event in page.items) {
                if (!first) writer.text(",")
                writer.text(json.encodeToString(event))
                first = false
            }
            val next = page.nextSequence ?: break
            if (sequence != null && next <= sequence) throw ActivityExportException(ActivityFailure.CorruptData)
            sequence = next
        }
        writer.text("]}")
        unchanged(record)
        writer.finish()
    }

    private suspend fun frozen(id: String): ActivityRecord {
        val record = activities.get(id).exportValue()
        if (record.status != ActivityStatus.COMPLETED && record.status != ActivityStatus.INTERRUPTED)
            throw ActivityExportException(ActivityFailure.ActivityBusy)
        return record
    }
    private suspend fun unchanged(record: ActivityRecord) {
        if (activities.get(record.id).exportValue().revision != record.revision) throw ActivityExportException(ActivityFailure.RevisionConflict)
    }
}

internal class ActivityExportWriter(private val sink: RawSink) {
    private var bytes = 0L
    private val pending = Buffer()
    suspend fun text(value: String) {
        currentCoroutineContext().ensureActive()
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        if (encoded.size > MAX_BYTES - bytes) throw ActivityExportException(ActivityFailure.CapacityExceeded)
        bytes += encoded.size
        pending.write(encoded)
        if (pending.size >= 65_536) sink.write(pending, pending.size)
    }
    fun finish() { if (pending.size > 0) sink.write(pending, pending.size); sink.flush() }
    suspend fun gpxStart(name: String) {
        if (name.any { (it.code < 32 && it != '\t' && it != '\r' && it != '\n') || it == '\uFFFE' || it == '\uFFFF' })
            throw ActivityExportException(ActivityFailure.InvalidInput("xmlName"))
        text("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"Bmaps\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>${xml(name)}</name>")
    }
    suspend fun point(latitude: Double, longitude: Double, epochMillis: Long?) {
        text("<trkpt lat=\"${decimal(latitude)}\" lon=\"${decimal(longitude)}\">")
        epochMillis?.let { text("<time>${Instant.fromEpochMilliseconds(it)}</time>") }
        text("</trkpt>")
    }
    private fun decimal(value: Double): String {
        val text = value.toString()
        val exponentAt = text.indexOf('E', ignoreCase = true)
        if (exponentAt < 0) return text
        val negative = text.startsWith('-')
        val mantissa = text.substring(0, exponentAt).removePrefix("-")
        val digits = mantissa.replace(".", "")
        val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it } + text.substring(exponentAt + 1).toInt()
        val expanded = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return if (negative) "-$expanded" else expanded
    }
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    companion object { const val MAX_BYTES = 512_000_000L }
}

internal fun <T> ActivityResult<T>.exportValue(): T = when (this) {
    is ActivityResult.Success -> value
    is ActivityResult.Failure -> throw ActivityExportException(reason)
}
