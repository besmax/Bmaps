package bes.max.bmaps

import dev.zacsweers.metrox.viewmodel.ViewModelGraph

internal interface AppGraph : ViewModelGraph {
    val providerRepository: bes.max.bmaps.domain.providers.ProviderRepository
    val onlineTileSourceFactory: bes.max.bmaps.domain.providers.OnlineTileSourceFactory
    val providerCredentials: bes.max.bmaps.core.datastore.ProviderCredentials
}
