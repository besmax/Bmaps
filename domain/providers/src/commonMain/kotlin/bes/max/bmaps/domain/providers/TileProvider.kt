package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.TileContentDescriptor
import kotlinx.serialization.Serializable

@Serializable
data class ProviderId(val value: String)

@Serializable
data class StyleId(val value: String)

@Serializable
data class ProviderStyleId(val provider: ProviderId, val style: StyleId)

@Serializable
data class Attribution(
    val text: String,
    val url: String,
    val logoUrl: String? = null,
    val requiresLogo: Boolean = false,
)

@Serializable
enum class OfflineDownloadPermission { ALLOWED, PROHIBITED, REQUIRES_VERIFICATION }

@Serializable
data class ProviderCapabilities(
    val onlineViewing: Boolean = true,
    val offlineDownload: OfflineDownloadPermission = OfflineDownloadPermission.REQUIRES_VERIFICATION,
    val policyUrl: String,
    val maxConcurrentRequests: Int? = null,
    val requestsPerSecond: Double? = null,
    val requiresIdentifyingUserAgent: Boolean = false,
)

@Serializable
data class TileStyle(
    val id: StyleId,
    val name: String,
    val endpoint: TileEndpoint,
    val content: TileContentDescriptor = TileContentDescriptor(),
    val configOverride: ProviderConfig? = null,
    val attributionOverride: List<Attribution>? = null,
    val capabilitiesOverride: ProviderCapabilities? = null,
)

@Serializable
data class TileProvider(
    val id: ProviderId,
    val name: String,
    val styles: List<TileStyle>,
    val config: ProviderConfig = ProviderConfig(),
    val attribution: List<Attribution>,
    val capabilities: ProviderCapabilities,
) {
    fun configFor(style: TileStyle): ProviderConfig = style.configOverride ?: config
    fun attributionFor(style: TileStyle): List<Attribution> = style.attributionOverride ?: attribution
    fun capabilitiesFor(style: TileStyle): ProviderCapabilities = style.capabilitiesOverride ?: capabilities
}

interface ProviderRepository {
    suspend fun list(): List<TileProvider>
    suspend fun find(id: ProviderId): TileProvider?
}
