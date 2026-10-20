/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder.repository

import bes.max.bmaps.core.mapengine.TileSource
import bes.max.bmaps.domain.mapbuilder.elevation.PackageElevation
import bes.max.bmaps.domain.mapbuilder.model.LayerId
import bes.max.bmaps.domain.mapbuilder.model.MapAvatar
import bes.max.bmaps.domain.mapbuilder.model.PackageId
import bes.max.bmaps.domain.mapbuilder.model.PackageManifest
import bes.max.bmaps.domain.mapbuilder.model.PackageResult
import bes.max.bmaps.domain.mapbuilder.model.PackageState
import bes.max.bmaps.domain.mapbuilder.model.PackageSummary
import kotlinx.coroutines.flow.Flow

data class PackageQuery(
    val nameContains: String = "",
    val states: Set<PackageState> = PackageState.entries.toSet(),
    val limit: Int = 50,
    val cursor: String? = null,
    val favouritesOnly: Boolean = false,
)

data class PackagePage(val items: List<PackageSummary>, val nextCursor: String?)

data class LayerPresentation(val id: LayerId, val visible: Boolean, val opacity: Double, val order: Int)

enum class PackageVerificationStage { WAITING, FILES, CHECKSUM, ANNOTATIONS, DATABASE, TILES }

data class PackageVerificationProgress(
    val stage: PackageVerificationStage,
    val asset: String? = null,
    val checkedTiles: Long = 0,
    val totalTiles: Long = 0,
)

interface PackageRepository {
    fun observe(query: PackageQuery): Flow<PackageResult<PackagePage>>
    fun observe(id: PackageId): Flow<PackageResult<PackageSummary>>
    suspend fun open(id: PackageId): PackageResult<OpenedPackage>
    suspend fun verifyFully(id: PackageId, onProgress: suspend (PackageVerificationProgress) -> Unit): PackageResult<Unit>
    suspend fun delete(id: PackageId): PackageResult<Unit>
    suspend fun setLayerPresentation(id: PackageId, layers: List<LayerPresentation>): PackageResult<Unit>
    suspend fun setFavourite(id: PackageId, favourite: Boolean): PackageResult<Unit>
    suspend fun setAvatar(id: PackageId, avatar: MapAvatar): PackageResult<Unit>
}

interface OpenedPackage {
    val manifest: PackageManifest
    suspend fun openTiles(layerId: LayerId): PackageResult<TileSource>
    suspend fun elevation(latitude: Double, longitude: Double): PackageElevation =
        if (manifest.elevation == null) PackageElevation.Missing else PackageElevation.Unsupported
    suspend fun close()
}
