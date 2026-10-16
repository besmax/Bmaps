/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.presentation

import androidx.compose.runtime.Immutable
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.library.generated.resources.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource

@Immutable
data class LibraryMapHeaderUi(val name: String, val avatar: MapAvatar)

@Immutable
data class LibraryBoundsUi(val south: Double, val west: Double, val north: Double, val east: Double)

@Immutable
data class LibraryMapUi(
    val id: String,
    val header: LibraryMapHeaderUi,
    val state: PackageState,
    val sizeBytes: Long,
    val hasElevationData: Boolean,
    val totalTiles: Long,
    val downloadedTiles: Long,
    val favourite: Boolean,
    val bounds: LibraryBoundsUi?,
    val zoomLevels: ImmutableList<Int>,
) {
    val name: String get() = header.name
    val avatar: MapAvatar get() = header.avatar
    val missingTiles: Long get() = (totalTiles - downloadedTiles).coerceAtLeast(0)
}

@Immutable
data class LibraryDownloadUi(
    val jobId: String,
    val packageId: String,
    val state: BuildJobState,
    val totalTiles: Long,
    val completedTiles: Long,
    val failureLabel: StringResource?,
    val needsElevationCredentials: Boolean,
) {
    val missingTiles: Long get() = (totalTiles - completedTiles).coerceAtLeast(0)
}

@Immutable
data class LibraryVerificationProgressUi(
    val stage: PackageVerificationStage = PackageVerificationStage.WAITING,
    val checkedTiles: Long = 0,
    val totalTiles: Long = 0,
)

internal fun PackageSummary.toLibraryUi() = LibraryMapUi(
    id = id.value,
    header = LibraryMapHeaderUi(name, MapAvatar.entries.firstOrNull { it.storageKey == avatarKey } ?: MapAvatar.MAP),
    state = state,
    sizeBytes = sizeBytes,
    hasElevationData = hasElevationData,
    totalTiles = totalTiles,
    downloadedTiles = downloadedTiles,
    favourite = favourite,
    bounds = bounds?.let { LibraryBoundsUi(it.south, it.west, it.north, it.east) },
    zoomLevels = zoomLevels.sorted().toImmutableList(),
)

internal fun BuildProgress.toLibraryUi() = LibraryDownloadUi(
    jobId = jobId.value,
    packageId = packageId.value,
    state = state,
    totalTiles = totalTiles,
    completedTiles = completedTiles,
    failureLabel = failure?.libraryLabel(),
    needsElevationCredentials = failure == PackageFailure.ElevationCredentialsRequired || failure == PackageFailure.ElevationAccessDenied,
)

internal fun PackageVerificationProgress.toLibraryUi() = LibraryVerificationProgressUi(stage, checkedTiles, totalTiles)

private fun PackageFailure.libraryLabel() = when (this) {
    PackageFailure.NetworkUnavailable -> Res.string.library_network_failed
    PackageFailure.AuthenticationRequired -> Res.string.library_credentials_required
    PackageFailure.ElevationCredentialsRequired -> Res.string.library_elevation_key_required
    PackageFailure.ElevationAccessDenied -> Res.string.library_elevation_access_denied
    PackageFailure.ElevationUnavailable -> Res.string.library_elevation_unavailable
    PackageFailure.ProviderDownloadNotAllowed -> Res.string.library_provider_blocked
    is PackageFailure.SizeLimitExceeded -> Res.string.library_size_limit
    is PackageFailure.InsufficientStorage -> Res.string.library_storage_full
    is PackageFailure.RateLimited -> Res.string.library_rate_limited
    PackageFailure.TileUnavailable -> Res.string.library_tiles_unavailable
    PackageFailure.ActivityInProgress -> Res.string.library_map_in_use
    PackageFailure.Conflict -> Res.string.library_source_changed
    else -> Res.string.library_action_failed
}
