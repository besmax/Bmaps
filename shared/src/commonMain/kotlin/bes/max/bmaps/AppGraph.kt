/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import dev.zacsweers.metrox.viewmodel.ViewModelGraph

internal interface AppGraph : ViewModelGraph {
    val providerRepository: bes.max.bmaps.domain.providers.ProviderRepository
    val onlineTileSourceFactory: bes.max.bmaps.domain.providers.OnlineTileSourceFactory
    val providerCredentials: bes.max.bmaps.core.datastore.ProviderCredentials
    val packageRepository: bes.max.bmaps.domain.mapbuilder.PackageRepository
    val packageBuildStorage: bes.max.bmaps.domain.mapbuilder.PackageBuildStorage
    val downloadExecutor: bes.max.bmaps.domain.mapbuilder.DownloadExecutor
    val downloadScheduler: bes.max.bmaps.domain.mapbuilder.DownloadScheduler
}
