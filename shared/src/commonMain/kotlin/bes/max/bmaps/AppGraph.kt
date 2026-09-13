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
