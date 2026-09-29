/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import androidx.compose.ui.window.ComposeUIViewController
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph

@DependencyGraph(AppScope::class)
internal interface IosAppGraph : AppGraph

private val appGraph: IosAppGraph by lazy { createGraph<IosAppGraph>() }

fun initializeDownloadScheduling() { appGraph.downloadScheduler.initialize() }

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun MainViewController() = ComposeUIViewController {
    App(appGraph, previewMap = kotlin.native.Platform.isDebugBinary &&
        platform.Foundation.NSProcessInfo.processInfo.arguments.contains("--preview-map"),
        onlineMap = kotlin.native.Platform.isDebugBinary && platform.Foundation.NSProcessInfo.processInfo.arguments.contains("--online-map"))
}
