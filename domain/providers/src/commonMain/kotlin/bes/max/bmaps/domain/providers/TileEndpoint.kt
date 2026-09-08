package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.TileKey
import kotlinx.serialization.Serializable

@Serializable
data class EndpointParameter(
    val name: String,
    val defaultValue: String? = null,
    val required: Boolean = false,
)

@Serializable
data class CredentialReference(val key: String, val queryParameter: String)

@Serializable
data class TileEndpoint(
    val urlTemplate: String,
    val queryTemplates: Map<String, String> = emptyMap(),
    val parameters: List<EndpointParameter> = emptyList(),
    val credential: CredentialReference? = null,
) {
    fun address(key: TileKey): TileAddress = TileAddress(
        url = urlTemplate.expand(key),
        query = queryTemplates.mapValues { (_, value) -> value.expand(key) },
        parameters = parameters,
        credential = credential,
    )
}

data class TileAddress(
    val url: String,
    val query: Map<String, String>,
    val parameters: List<EndpointParameter>,
    val credential: CredentialReference?,
)

private fun String.expand(key: TileKey): String = replace("{z}", key.level.toString())
    .replace("{x}", key.column.toString())
    .replace("{y}", key.row.toString())
