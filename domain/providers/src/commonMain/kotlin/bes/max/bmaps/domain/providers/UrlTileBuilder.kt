package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.TileKey
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

fun interface UrlTileBuilder {
    fun build(level: Int, row: Long, col: Long): String
}

open class EndpointUrlTileBuilder(
    private val endpoint: TileEndpoint,
    parameters: Map<String, String> = emptyMap(),
    private val credential: String? = null,
) : UrlTileBuilder {
    private val parameters = parameters.toMap()

    init {
        require(parameters.keys.all { name -> endpoint.parameters.any { it.name == name } }) { "Unknown endpoint parameter" }
        require(endpoint.parameters.map { it.name }.distinct().size == endpoint.parameters.size)
        require(endpoint.parameters.none { it.name in endpoint.queryTemplates || it.name == endpoint.credential?.queryParameter })
        require(endpoint.parameters.all { !it.required || !(parameters[it.name] ?: it.defaultValue).isNullOrBlank() }) {
            "Missing endpoint parameter"
        }
        require(endpoint.credential == null || !credential.isNullOrBlank()) { "Missing provider credential" }
    }

    override fun build(level: Int, row: Long, col: Long): String {
        val address = endpoint.address(TileKey(level, col, row))
        return URLBuilder(address.url).apply {
            require(protocol == URLProtocol.HTTPS && user == null && password == null && fragment.isEmpty())
            address.query.forEach { (name, value) -> parameters.append(name, value) }
            address.parameters.forEach { parameter ->
                (this@EndpointUrlTileBuilder.parameters[parameter.name] ?: parameter.defaultValue)?.let {
                    parameters.append(parameter.name, it)
                }
            }
            address.credential?.let { parameters.append(it.queryParameter, checkNotNull(credential)) }
        }.buildString()
    }

    override fun toString(): String = "UrlTileBuilder(<redacted>)"
}

class OsmUrlTileBuilder(endpoint: TileEndpoint, parameters: Map<String, String> = emptyMap()) :
    EndpointUrlTileBuilder(endpoint, parameters)

class ArcGisUrlTileBuilder(endpoint: TileEndpoint, parameters: Map<String, String> = emptyMap()) :
    EndpointUrlTileBuilder(endpoint, parameters)

class YandexUrlTileBuilder(endpoint: TileEndpoint, parameters: Map<String, String>, apiKey: String) :
    EndpointUrlTileBuilder(endpoint, parameters, apiKey)

class ThunderforestUrlTileBuilder(endpoint: TileEndpoint, parameters: Map<String, String>, apiKey: String) :
    EndpointUrlTileBuilder(endpoint, parameters, apiKey)

fun interface UrlTileBuilderFactory {
    fun create(endpoint: TileEndpoint, parameters: Map<String, String>, credential: String?): UrlTileBuilder
}
