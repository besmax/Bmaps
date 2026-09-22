package bes.max.bmaps.domain.mapbuilder

import kotlinx.serialization.Serializable

@Serializable
sealed interface PackageFailure {
    @Serializable
    data object NotFound : PackageFailure
    @Serializable
    data object NotReady : PackageFailure
    @Serializable
    data object CorruptData : PackageFailure
    @Serializable
    data class UnsupportedVersion(val version: Int) : PackageFailure
    @Serializable
    data object UnsupportedContent : PackageFailure
    @Serializable
    data object UnsupportedCoordinateSystem : PackageFailure
    @Serializable
    data object NetworkUnavailable : PackageFailure
    @Serializable
    data object AuthenticationRequired : PackageFailure
    @Serializable
    data object ElevationCredentialsRequired : PackageFailure
    @Serializable
    data object ElevationAccessDenied : PackageFailure
    @Serializable
    data object ElevationUnavailable : PackageFailure
    @Serializable
    data class RateLimited(val retryAfterMillis: Long?) : PackageFailure
    @Serializable
    data object ProviderDownloadNotAllowed : PackageFailure
    @Serializable
    data object TileUnavailable : PackageFailure
    @Serializable
    data class SizeLimitExceeded(val limitBytes: Long, val requiredBytes: Long?) : PackageFailure
    @Serializable
    data class InsufficientStorage(val requiredBytes: Long?) : PackageFailure
    @Serializable
    data object Io : PackageFailure
    @Serializable
    data object Conflict : PackageFailure
}

sealed interface PackageResult<out T> {
    data class Success<T>(val value: T) : PackageResult<T>
    data class Failure(val reason: PackageFailure) : PackageResult<Nothing>
}
