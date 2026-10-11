/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.ui

import bes.max.bmaps.domain.mapbuilder.MapAvatar
import bes.max.bmaps.domain.mapbuilder.PackageState
import bmaps.feature.library.generated.resources.*

internal fun stateLabel(state: PackageState) = when (state) {
    PackageState.READY -> Res.string.library_ready
    PackageState.BUILDING -> Res.string.library_downloading
    PackageState.FINALIZING -> Res.string.library_finalizing
    PackageState.PAUSED -> Res.string.library_paused
    PackageState.FAILED -> Res.string.library_failed
    PackageState.DELETING -> Res.string.library_deleting
    PackageState.CORRUPT, PackageState.MISSING -> Res.string.library_damaged
}

internal fun megabytes(bytes: Long): String = "${bytes / 1_000_000}.${bytes / 100_000 % 10}"

internal fun avatarLabel(avatar: MapAvatar) = when (avatar) {
    MapAvatar.MAP -> Res.string.avatar_map
    MapAvatar.MOUNTAIN -> Res.string.avatar_mountain
    MapAvatar.FOREST -> Res.string.avatar_forest
    MapAvatar.WATER -> Res.string.avatar_water
    MapAvatar.CITY -> Res.string.avatar_city
    MapAvatar.CAMP -> Res.string.avatar_camp
}

internal fun avatarIcon(avatar: MapAvatar) = when (avatar) {
    MapAvatar.MAP -> Res.drawable.avatar_map
    MapAvatar.MOUNTAIN -> Res.drawable.avatar_mountain
    MapAvatar.FOREST -> Res.drawable.avatar_forest
    MapAvatar.WATER -> Res.drawable.avatar_water
    MapAvatar.CITY -> Res.drawable.avatar_city
    MapAvatar.CAMP -> Res.drawable.avatar_camp
}
