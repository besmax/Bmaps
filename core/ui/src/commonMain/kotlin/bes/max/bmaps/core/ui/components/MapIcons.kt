/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.ui.components

import bmaps.core.ui.generated.resources.Res
import bmaps.core.ui.generated.resources.ic_my_location
import bmaps.core.ui.generated.resources.ic_layers
import bmaps.core.ui.generated.resources.ic_arrow_back
import bmaps.core.ui.generated.resources.ic_zoom_in
import bmaps.core.ui.generated.resources.ic_zoom_out
import org.jetbrains.compose.resources.DrawableResource

object MapIcons {
    val myLocation: DrawableResource get() = Res.drawable.ic_my_location
    val layers: DrawableResource get() = Res.drawable.ic_layers
    val back: DrawableResource get() = Res.drawable.ic_arrow_back
    val zoomIn: DrawableResource get() = Res.drawable.ic_zoom_in
    val zoomOut: DrawableResource get() = Res.drawable.ic_zoom_out
}
