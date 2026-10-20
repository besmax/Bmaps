/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.navigation

import bes.max.bmaps.domain.activity.model.ActivityFailure
import bes.max.bmaps.domain.activity.model.ActivityLimits
import bes.max.bmaps.domain.activity.model.ActivityRecord
import bes.max.bmaps.domain.activity.model.ActivityResult
import bes.max.bmaps.domain.activity.model.ActivityRoute
import bes.max.bmaps.domain.activity.model.ActivityValidation
import bes.max.bmaps.domain.activity.model.PositionMeasurement
import bes.max.bmaps.domain.activity.model.RouteSegment
import bes.max.bmaps.domain.activity.model.RouteVertex
import bes.max.bmaps.domain.activity.repository.ActivityRepository
import bes.max.bmaps.domain.activity.repository.owned
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object RecordedReturnRoute {
    suspend fun build(record: ActivityRecord, id: String, repository: ActivityRepository): ActivityResult<ActivityRoute> {
        val segments = mutableListOf<RouteSegment>()
        var segmentId: String? = null
        var vertices = mutableListOf<RouteVertex>()
        var cursor = 0L
        var count = 0
        fun finish() {
            if (vertices.size >= 2) segments += RouteSegment(checkNotNull(segmentId), vertices.toList())
            vertices = mutableListOf()
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = repository.positions(record.id, ActivityPositionQuery(cursor, 500))
            if (result is ActivityResult.Failure) return result
            val page = (result as ActivityResult.Success<ActivityPositionPage>).value
            for (sample in page.items) {
                if (++count > ActivityLimits.MAX_ROUTE_VERTICES) return ActivityResult.Failure(ActivityFailure.CapacityExceeded)
                val position = sample.measurement as? PositionMeasurement ?: return ActivityResult.Failure(ActivityFailure.CorruptData)
                if (ActivityValidation.sample(sample) != null) return ActivityResult.Failure(ActivityFailure.CorruptData)
                if (sample.pathSegmentId != segmentId) {
                    finish(); segmentId = sample.pathSegmentId
                    if (segments.size >= ActivityLimits.MAX_ROUTE_SEGMENTS) return ActivityResult.Failure(ActivityFailure.CapacityExceeded)
                }
                if (vertices.lastOrNull()?.coordinate != position.coordinate)
                    vertices += RouteVertex(position.coordinate, sourceTimeEpochMillis = sample.acquiredAt.epochMillis)
            }
            val next = page.nextSequence ?: break
            if (next <= cursor) return ActivityResult.Failure(ActivityFailure.CorruptData)
            cursor = next
        }
        if (vertices.size < 2) return ActivityResult.Failure(ActivityFailure.InvalidInput("returnSegmentTooShort"))
        finish()
        val route = ActivityRoute(id, 0, record.name, segments).owned()
        ActivityValidation.route(route)?.let { return ActivityResult.Failure(ActivityFailure.InvalidInput(it)) }
        return ActivityResult.Success(route)
    }
}
