/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.activity.model

import bes.max.bmaps.domain.activity.recording.ActivityAction

sealed interface ActivityResult<out T> {
    data class Success<T>(val value: T) : ActivityResult<T>
    data class Failure(val reason: ActivityFailure) : ActivityResult<Nothing>
}

sealed interface ActivityFailure {
    data class InvalidInput(val field: String) : ActivityFailure
    data class InvalidTransition(val status: ActivityStatus, val action: ActivityAction) : ActivityFailure
    data object NotFound : ActivityFailure
    data object AnotherActivityActive : ActivityFailure
    data object RevisionConflict : ActivityFailure
    data object StaleSession : ActivityFailure
    data object ForegroundRequired : ActivityFailure
    data object RecordingUnavailable : ActivityFailure
    data object PermissionRequired : ActivityFailure
    data object PreciseLocationRequired : ActivityFailure
    data object LocationDisabled : ActivityFailure
    data object CapacityExceeded : ActivityFailure
    data object StorageUnavailable : ActivityFailure
    data object UnsupportedVersion : ActivityFailure
    data object CorruptData : ActivityFailure
    data object ActivityBusy : ActivityFailure
}
