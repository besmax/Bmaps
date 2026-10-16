/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity

@kotlinx.serialization.Serializable
enum class ActivityAction { PAUSE, RESUME, FINISH, INTERRUPT, COMPLETE, MARK_CONTROL_POINT }

object ActivityLifecycle {
    fun next(status: ActivityStatus, action: ActivityAction): ActivityStatus? = when (action) {
        ActivityAction.PAUSE -> when (status) {
            ActivityStatus.RECORDING, ActivityStatus.PAUSED -> ActivityStatus.PAUSED
            else -> null
        }
        ActivityAction.RESUME -> when (status) {
            ActivityStatus.RECORDING, ActivityStatus.PAUSED, ActivityStatus.INTERRUPTED -> ActivityStatus.RECORDING
            else -> null
        }
        ActivityAction.FINISH -> when (status) {
            ActivityStatus.RECORDING, ActivityStatus.PAUSED, ActivityStatus.INTERRUPTED,
            ActivityStatus.FINALIZING -> ActivityStatus.FINALIZING
            ActivityStatus.COMPLETED -> null
        }
        ActivityAction.INTERRUPT -> when (status) {
            ActivityStatus.RECORDING, ActivityStatus.PAUSED, ActivityStatus.INTERRUPTED -> ActivityStatus.INTERRUPTED
            else -> null
        }
        ActivityAction.MARK_CONTROL_POINT -> status.takeIf { it == ActivityStatus.RECORDING }
        ActivityAction.COMPLETE -> when (status) {
            ActivityStatus.FINALIZING, ActivityStatus.COMPLETED -> ActivityStatus.COMPLETED
            else -> null
        }
    }
}
