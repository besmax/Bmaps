package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.datastore.CredentialResult
import bes.max.bmaps.core.datastore.ProviderCredentials
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.TileSource
import bes.max.bmaps.core.network.HttpTransport
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface OnlineSourceResult {
    class Available(val source: TileSource) : OnlineSourceResult
    data class Failed(val reason: OnlineSourceFailure) : OnlineSourceResult
}

enum class OnlineSourceFailure {
    UNKNOWN_PROVIDER, UNKNOWN_STYLE, ONLINE_DISABLED, MISSING_CREDENTIAL, CREDENTIAL_UNAVAILABLE, INVALID_CONFIGURATION,
}

fun interface OnlineSourceOpener {
    suspend fun open(id: ProviderStyleId): OnlineSourceResult
}

@Inject
@dev.zacsweers.metro.ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class OnlineTileSourceFactory(
    private val registry: ProviderRegistry,
    private val credentials: ProviderCredentials,
    private val transport: HttpTransport,
) : OnlineSourceOpener {
    override suspend fun open(id: ProviderStyleId): OnlineSourceResult = open(id, emptyMap())

    private val gates = mutableMapOf<ProviderId, ProviderRequestGate>()
    private val lock = Mutex()

    suspend fun open(id: ProviderStyleId, parameters: Map<String, String>): OnlineSourceResult {
        val registration = registry.registration(id.provider)
            ?: return OnlineSourceResult.Failed(OnlineSourceFailure.UNKNOWN_PROVIDER)
        val provider = registration.provider
        val style = provider.styles.find { it.id == id.style }
            ?: return OnlineSourceResult.Failed(OnlineSourceFailure.UNKNOWN_STYLE)
        if (!provider.capabilitiesFor(style).onlineViewing) return OnlineSourceResult.Failed(OnlineSourceFailure.ONLINE_DISABLED)
        val credential = style.endpoint.credential?.let {
            when (val result = credentials.read(it.key)) {
                is CredentialResult.Available -> result.value
                CredentialResult.Missing -> return OnlineSourceResult.Failed(OnlineSourceFailure.MISSING_CREDENTIAL)
                CredentialResult.Unavailable -> return OnlineSourceResult.Failed(OnlineSourceFailure.CREDENTIAL_UNAVAILABLE)
            }
        }
        val builder = try { registration.urlBuilderFactory.create(style.endpoint, parameters, credential) }
        catch (_: IllegalArgumentException) { return OnlineSourceResult.Failed(OnlineSourceFailure.INVALID_CONFIGURATION) }
        val gate = lock.withLock { gates.getOrPut(provider.id) { ProviderRequestGate(provider) } }
        return OnlineSourceResult.Available(OnlineTileSource(
            builder, provider.configFor(style), style.content, provider.requestPolicy, gate, transport,
        ))
    }
}
