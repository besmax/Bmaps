package bes.max.bmaps.domain.providers

import bes.max.bmaps.core.mapengine.BoundingBox
import bes.max.bmaps.core.mapengine.CoordinateSystemId
import bes.max.bmaps.core.mapengine.TileRowOrigin
import kotlinx.serialization.Serializable

@Serializable
data class BoundariesConfig(val boundingBoxList: List<BoundingBox> = emptyList())

@Serializable
data class InitScaleAndScrollConfig(
    val scale: Double = 1.0,
    val scrollX: Double = 0.5,
    val scrollY: Double = 0.5,
)

@Serializable
data class ScaleLimitsConfig(val minScale: Double? = null, val maxScale: Double? = null)

@Serializable
data class LevelLimitsConfig(val levelMin: Int = 0, val levelMax: Int? = null)

@Serializable
data class TileMatrixConfig(
    val coordinateSystem: CoordinateSystemId = CoordinateSystemId.WebMercator,
    val rowOrigin: TileRowOrigin = TileRowOrigin.TOP,
    val tileWidth: Int = 256,
    val tileHeight: Int = 256,
)

@Serializable
data class ProviderConfig(
    val boundaries: BoundariesConfig = BoundariesConfig(),
    val initialViewport: InitScaleAndScrollConfig = InitScaleAndScrollConfig(),
    val scaleLimits: ScaleLimitsConfig = ScaleLimitsConfig(),
    val levelLimits: LevelLimitsConfig = LevelLimitsConfig(),
    val tileMatrix: TileMatrixConfig = TileMatrixConfig(),
)
