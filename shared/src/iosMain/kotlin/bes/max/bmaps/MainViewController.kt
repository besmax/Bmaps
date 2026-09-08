package bes.max.bmaps

import androidx.compose.ui.window.ComposeUIViewController
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph

@DependencyGraph(AppScope::class)
internal interface IosAppGraph : AppGraph

private val appGraph: IosAppGraph by lazy { createGraph<IosAppGraph>() }

fun MainViewController() = ComposeUIViewController { App(appGraph) }
