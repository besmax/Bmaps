/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import bes.max.bmaps.core.mapengine.MapPoint
import bes.max.bmaps.core.mapengine.TilePyramid
import bes.max.bmaps.core.mapengine.positionOf
import bes.max.bmaps.core.sharing.SharedDocumentStore
import bes.max.bmaps.domain.activity.*
import bes.max.bmaps.feature.viewer.activity.presentation.TrackCoordinate
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.math.abs

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ReviewViewModel(private val reader: ActivityReviewReader, private val activities: ActivityRepository,
    private val exporter: ActivityExporter, private val files: SharedDocumentStore) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<HistoryEvent>(Channel.BUFFERED)
    internal val events = channel.receiveAsFlow()
    private var loading: Job? = null
    private var selecting: Job? = null
    private var exporting: Job? = null
    private var generation = 0
    fun open(id: String) {
        if (state.value.activityId == id) return
        load(id)
    }
    fun retry() { state.value.activityId?.let(::load) }
    private fun load(id: String) {
        val token = ++generation
        loading?.cancel(); selecting?.cancel()
        mutableState.value = ReviewState(activityId = id, loading = true)
        loading = viewModelScope.launch {
            try {
                val review = withContext(Dispatchers.Default) { reader.read(id) }
                if (token == generation) mutableState.value = review
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ReviewFailure) { if (token == generation) mutableState.update { it.copy(loading = false, error = historyFailure(failure.reason)) } }
            catch (_: Exception) { if (token == generation) mutableState.update { it.copy(loading = false, error = Res.string.history_error) } }
        }
    }
    fun fitted() { mutableState.update { it.copy(fitPending = false) } }
    fun panel(expanded: Boolean) { mutableState.update { it.copy(panelExpanded = expanded) } }
    fun eventList(show: Boolean) { mutableState.update { it.copy(eventList = show) } }
    fun series(index: Int) { if (index in state.value.series.indices) mutableState.update { it.copy(selectedSeries = index) } }
    fun selectTime(time: Long, sampleId: String? = null) {
        val id = state.value.activityId ?: return
        if (time < 0) return
        val token = generation
        selecting?.cancel()
        mutableState.update { it.copy(selectedTime = time, selectedPosition = null, selectedPositionTime = null, selectedValue = null, selectedSource = null) }
        selecting = viewModelScope.launch {
            try {
                val candidates = mutableListOf<TelemetrySample>()
                var cursor: TelemetryCursor? = null
                var selectedSample: TelemetrySample? = null
                var readCount = 0
                val from = (time - 5000).coerceAtLeast(0)
                val to = if (time > Long.MAX_VALUE - 5000) Long.MAX_VALUE else time + 5000
                do {
                    val page = activities.telemetry(id, TelemetryQuery(from, to, cursor, 500)).valueOrThrow()
                    selectedSample = selectedSample ?: page.items.firstOrNull { it.id == sampleId }
                    readCount += page.items.size
                    if (readCount > 5000) throw ReviewFailure(ActivityFailure.CapacityExceeded)
                    candidates.addAll(page.items.filter { it.measurement is PositionMeasurement })
                    if (candidates.size > 5000) throw ReviewFailure(ActivityFailure.CapacityExceeded)
                    cursor = page.next
                } while (cursor != null)
                val explicit = sampleId?.let { target -> candidates.firstOrNull { it.id == target } }
                val nearest = explicit ?: candidates.minByOrNull { abs(it.acquiredAt.epochMillis - time) }?.takeIf { best ->
                    candidates.count { abs(it.acquiredAt.epochMillis - time) == abs(best.acquiredAt.epochMillis - time) } == 1
                }
                val coordinate = (nearest?.measurement as? PositionMeasurement)?.coordinate
                val chosen = selectedSample ?: nearest
                val value = when (val measurement = chosen?.measurement) {
                    is SpeedMeasurement -> "${reviewNumber(measurement.metersPerSecond * 3.6)} km/h"
                    is HeartRateMeasurement -> "${reviewNumber(measurement.beatsPerMinute)} bpm"
                    is ElevationMeasurement -> "${reviewNumber(measurement.meters)} m · ${measurement.verticalReference}"
                    else -> null
                }
                if (token == generation) mutableState.update { it.copy(selectedPosition = coordinate?.let { point -> TrackCoordinate(point.latitude, point.longitude) },
                    selectedPositionTime = nearest?.acquiredAt?.epochMillis, selectedValue = value,
                    selectedSource = chosen?.source?.let { it.provider ?: it.id }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) mutableState.update { it.copy(error = Res.string.history_error) } }
        }
    }
    fun selectMap(pathId: String, point: MapPoint, pyramid: TilePyramid?) {
        if (pyramid == null || !point.isValid || !pathId.startsWith("track:recorded:")) return
        val segment = pathId.removePrefix("track:recorded:").substringBeforeLast(':')
        val positions = state.value.positions
        val token = generation
        selecting?.cancel()
        selecting = viewModelScope.launch {
            val nearest = withContext(Dispatchers.Default) {
                positions.asSequence().filter { it.segment == segment }.minByOrNull { position ->
                    val projected = pyramid.positionOf(GeographicCoordinate(position.coordinate.latitude, position.coordinate.longitude))
                    if (projected == null) Double.POSITIVE_INFINITY else (projected.x - point.x) * (projected.x - point.x) + (projected.y - point.y) * (projected.y - point.y)
                }
            }
            if (token == generation) {
                selecting = null
                nearest?.let { selectTime(it.time, it.id) }
            }
        }
    }
    fun exportOptions(show: Boolean) {
        if (!show && state.value.busy) {
            exporting?.invokeOnCompletion { mutableState.update { it.copy(exporting = false) } }
            exporting?.cancel()
            return
        }
        if (!state.value.busy) mutableState.update { it.copy(exporting = show) }
    }
    fun nativeFailure() { mutableState.update { it.copy(error = Res.string.history_share_error) } }
    fun export(kind: Int) {
        val id = state.value.activityId ?: return
        if (state.value.busy || kind !in 0..1) return
        mutableState.update { it.copy(busy = true, error = null) }
        exporting = viewModelScope.launch {
            try {
                val document = files.create(if (kind == 1) "json" else "gpx", if (kind == 1) "application/json" else "application/gpx+xml") { sink ->
                    if (kind == 1) exporter.telemetry(id, sink) else exporter.recordedPath(id, sink)
                }
                mutableState.update { it.copy(exporting = false) }
                channel.send(HistoryEvent.Share(document))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ActivityExportException) { mutableState.update { it.copy(error = historyFailure(failure.reason)) } }
            catch (_: Exception) { nativeFailure() }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
