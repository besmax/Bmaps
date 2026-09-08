package bes.max.bmaps.domain.mapbuilder

sealed interface PackageFailure {
    data object NotFound : PackageFailure
    data object NotReady : PackageFailure
    data object CorruptData : PackageFailure
    data class UnsupportedVersion(val version: Int) : PackageFailure
    data object UnsupportedContent : PackageFailure
    data object UnsupportedCoordinateSystem : PackageFailure
    data object NetworkUnavailable : PackageFailure
    data object AuthenticationRequired : PackageFailure
    data class RateLimited(val retryAfterMillis: Long?) : PackageFailure
    data object ProviderDownloadNotAllowed : PackageFailure
    data object TileUnavailable : PackageFailure
    data class SizeLimitExceeded(val limitBytes: Long, val requiredBytes: Long?) : PackageFailure
    data class InsufficientStorage(val requiredBytes: Long?) : PackageFailure
    data object Io : PackageFailure
    data object Conflict : PackageFailure
}

sealed interface PackageResult<out T> {
    data class Success<T>(val value: T) : PackageResult<T>
    data class Failure(val reason: PackageFailure) : PackageResult<Nothing>
}
