/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface PackageFailure {
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.NotFound")
    data object NotFound : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.NotReady")
    data object NotReady : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.CorruptData")
    data object CorruptData : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.UnsupportedVersion")
    data class UnsupportedVersion(val version: Int) : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.UnsupportedContent")
    data object UnsupportedContent : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.UnsupportedCoordinateSystem")
    data object UnsupportedCoordinateSystem : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.NetworkUnavailable")
    data object NetworkUnavailable : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.AuthenticationRequired")
    data object AuthenticationRequired : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.ElevationCredentialsRequired")
    data object ElevationCredentialsRequired : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.ElevationAccessDenied")
    data object ElevationAccessDenied : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.ElevationUnavailable")
    data object ElevationUnavailable : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.RateLimited")
    data class RateLimited(val retryAfterMillis: Long?) : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.ProviderDownloadNotAllowed")
    data object ProviderDownloadNotAllowed : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.TileUnavailable")
    data object TileUnavailable : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.SizeLimitExceeded")
    data class SizeLimitExceeded(val limitBytes: Long, val requiredBytes: Long?) : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.InsufficientStorage")
    data class InsufficientStorage(val requiredBytes: Long?) : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.Io")
    data object Io : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.Conflict")
    data object Conflict : PackageFailure
    @Serializable
    @SerialName("bes.max.bmaps.domain.mapbuilder.PackageFailure.ActivityInProgress")
    data object ActivityInProgress : PackageFailure
}

sealed interface PackageResult<out T> {
    data class Success<T>(val value: T) : PackageResult<T>
    data class Failure(val reason: PackageFailure) : PackageResult<Nothing>
}
