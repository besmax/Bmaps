/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.history.presentation

import androidx.compose.runtime.Immutable
import bes.max.bmaps.feature.viewer.activity.presentation.TrackCoordinate
import bes.max.bmaps.feature.viewer.activity.presentation.TrackLine
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource

@Immutable
internal data class HistoryItem(val id: String, val name: String, val date: String, val status: StringResource,
    val profile: StringResource, val revision: Long, val editable: Boolean, val active: Boolean, val exportable: Boolean)
@Immutable
internal data class SavedRouteItem(val id: String, val name: String, val revision: Long, val points: Int, val segments: Int)
@Immutable
internal data class HistoryState(
    val routesTab: Boolean = false, val search: String = "", val filter: Int = 0, val busy: Boolean = false,
    val loadingVisible: Boolean = false,
    val activities: ImmutableList<HistoryItem> = persistentListOf(), val routes: ImmutableList<SavedRouteItem> = persistentListOf(),
    val activeItem: HistoryItem? = null,
    val more: Boolean = false, val activeId: String? = null, val error: StringResource? = null,
    val editingId: String? = null, val editingRoute: Boolean = false, val draftName: String = "",
    val deletingId: String? = null, val deletingRoute: Boolean = false,
    val exportingId: String? = null, val exportingRoute: Boolean = false,
)
@Immutable
internal data class ReviewPoint(val id: String, val time: Long, val coordinate: TrackCoordinate, val segment: String)
@Immutable
internal data class ChartPoint(val id: String, val time: Long, val value: Double, val run: Int)
@Immutable
internal data class ReviewSeries(val id: String, val metric: StringResource, val source: String, val unit: String,
    val points: ImmutableList<ChartPoint>, val count: Long, val minimum: String, val maximum: String, val mean: String)
@Immutable
internal data class ReviewEvent(val id: String, val time: Long, val label: StringResource, val relatedSampleId: String?)
@Immutable
internal data class ReviewState(
    val revision: Long = 0, val date: String = "", val profile: StringResource? = null, val packageId: String? = null,
    val averageSpeed: String? = null, val maximumSpeed: String? = null,
    val actionsMenu: Boolean = false, val renaming: Boolean = false, val draftName: String = "", val deleting: Boolean = false, val deleted: Boolean = false,
    val actionError: StringResource? = null,
    val activityId: String? = null, val name: String = "", val status: StringResource? = null,
    val loading: Boolean = false, val error: StringResource? = null, val busy: Boolean = false,
    val distance: String = "", val elapsed: String = "", val recording: String = "", val moving: String = "",
    val heartRateAvailable: Boolean = false, val fromTime: Long = 0, val toTime: Long = 1, val exportable: Boolean = false,
    val interrupted: String = "", val estimated: Boolean = false, val editable: Boolean = false,
    val planned: ImmutableList<TrackLine> = persistentListOf(), val recorded: ImmutableList<TrackLine> = persistentListOf(),
    val positions: ImmutableList<ReviewPoint> = persistentListOf(), val series: ImmutableList<ReviewSeries> = persistentListOf(),
    val events: ImmutableList<ReviewEvent> = persistentListOf(), val selectedSeries: Int = 0,
    val selectedValue: String? = null, val selectedSource: String? = null,
    val selectedTime: Long? = null, val selectedPosition: TrackCoordinate? = null, val selectedPositionTime: Long? = null,
    val fitPending: Boolean = true, val panelExpanded: Boolean = false, val eventList: Boolean = false, val exporting: Boolean = false,
)
