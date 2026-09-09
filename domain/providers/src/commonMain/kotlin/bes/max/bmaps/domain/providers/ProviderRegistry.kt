package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

class ProviderRegistration(val provider: TileProvider, val urlBuilderFactory: UrlTileBuilderFactory =
    UrlTileBuilderFactory { endpoint, parameters, credential -> EndpointUrlTileBuilder(endpoint, parameters, credential) })

class ProviderRegistry(registrations: List<ProviderRegistration>) : ProviderRepository {
    private val registrations = registrations.toList()
    private val byId = this.registrations.associateBy { it.provider.id }

    init {
        require(registrations.all { entry ->
            val provider = entry.provider
            provider.styles.isNotEmpty() && provider.styles.all { style ->
                val capability = provider.capabilitiesFor(style)
                val levels = provider.configFor(style).levelLimits
                (capability.maxConcurrentRequests == null || capability.maxConcurrentRequests > 0) &&
                    (capability.requestsPerSecond == null || (capability.requestsPerSecond.isFinite() && capability.requestsPerSecond > 0)) &&
                    levels.levelMin >= 0 && (levels.levelMax == null || levels.levelMax >= levels.levelMin)
            }
        }) { "Invalid provider configuration" }
        require(byId.size == registrations.size) { "Duplicate provider ID" }
        require(registrations.all { entry -> entry.provider.styles.map { it.id }.distinct().size == entry.provider.styles.size }) {
            "Duplicate style ID"
        }
    }

    override suspend fun list(): List<TileProvider> = registrations.map { it.provider }
    override suspend fun find(id: ProviderId): TileProvider? = byId[id]?.provider
    fun registration(id: ProviderId): ProviderRegistration? = byId[id]

    companion object {
        fun builtIn(): ProviderRegistry = ProviderRegistry(listOf(
            ProviderRegistration(BuiltInProviders.osm) { endpoint, parameters, _ -> OsmUrlTileBuilder(endpoint, parameters) },
            ProviderRegistration(BuiltInProviders.arcGis) { endpoint, parameters, _ -> ArcGisUrlTileBuilder(endpoint, parameters) },
            ProviderRegistration(BuiltInProviders.yandex) { endpoint, parameters, credential ->
                YandexUrlTileBuilder(endpoint, parameters, checkNotNull(credential))
            },
            ProviderRegistration(BuiltInProviders.thunderforest) { endpoint, parameters, credential ->
                ThunderforestUrlTileBuilder(endpoint, parameters, checkNotNull(credential))
            },
        ))
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RegisteredProviderRepository(private val registry: ProviderRegistry) : ProviderRepository by registry

@dev.zacsweers.metro.BindingContainer
@dev.zacsweers.metro.ContributesTo(AppScope::class)
object ProviderBindings {
    @dev.zacsweers.metro.Provides
    @SingleIn(AppScope::class)
    fun registry(): ProviderRegistry = ProviderRegistry.builtIn()
}
