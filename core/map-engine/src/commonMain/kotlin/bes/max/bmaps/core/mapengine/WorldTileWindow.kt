package bes.max.bmaps.core.mapengine

import kotlin.math.floor

fun TilePyramid.worldViewport(viewport: MapViewport): MapViewport {
    val side = (1L shl levels.min).toDouble()
    return MapViewport(MapPoint((originColumn + viewport.center.x * columns) / side,
        (originRow + viewport.center.y * rows) / side), viewport.scale * side / columns)
}

fun TilePyramid.localViewport(world: MapViewport): MapViewport {
    val side = (1L shl levels.min).toDouble()
    return MapViewport(MapPoint((world.center.x * side - originColumn) / columns,
        (world.center.y * side - originRow) / rows), world.scale * columns / side)
}

fun TilePyramid.worldWindow(window: MapWindow): MapWindow {
    val side = (1L shl levels.min).toDouble()
    return MapWindow((originColumn + window.left * columns) / side, (originRow + window.top * rows) / side,
        (originColumn + window.right * columns) / side, (originRow + window.bottom * rows) / side)
}

fun worldTileWindow(levels: ZoomRange, tileSize: Int, world: MapViewport, previous: TilePyramid? = null): TilePyramid? {
    if (levels.min !in 0..30 || levels.max !in levels.min..30 || !world.center.isValid) return null
    val global = TilePyramid(levels, columns = 1 shl levels.min, rows = 1 shl levels.min, tileSize = tileSize)
    if (global.engineSize() != null) return global
    if (levels.max > 30 || levels.min > 8) return null
    val regional = world.scale >= if (previous?.levels?.min == 8) 16_384 else 32_768
    if (!regional) {
        val maxGlobal = (levels.max downTo levels.min).firstOrNull {
            global.copy(levels = ZoomRange(levels.min, it)).engineSize() != null
        } ?: return null
        return global.copy(levels = ZoomRange(levels.min, maxGlobal))
    }
    if (previous?.levels?.min == 8) {
        val local = previous.localViewport(world).center
        if (local.x in 0.25..0.75 && local.y in 0.25..0.75) return previous
    }
    return TilePyramid(ZoomRange(8, levels.max),
        originColumn = (floor(world.center.x * 256).toLong() - 2).coerceIn(0, 252),
        originRow = (floor(world.center.y * 256).toLong() - 2).coerceIn(0, 252),
        columns = 4, rows = 4, tileSize = tileSize).takeIf { it.engineSize() != null }
}
