/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

class BmapsApplication : Application(), androidx.work.Configuration.Provider {
    internal val graph: AndroidAppGraph by lazy {
        createGraphFactory<AndroidAppGraph.Factory>().create(applicationContext)
    }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(graph.downloadWorkerFactory).build()
    override fun onCreate() {
        super.onCreate()
        graph.downloadScheduler.initialize()
        graph.elevationGenerationScheduler.initialize()
    }
}

@DependencyGraph(AppScope::class)
internal interface AndroidAppGraph : AppGraph {
    val downloadWorkerFactory: bes.max.bmaps.domain.mapbuilder.MapDownloadWorkerFactory
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AndroidAppGraph
    }
}

@Composable
fun App(fixtureMap: Boolean = false) {
    val application = LocalContext.current.applicationContext as BmapsApplication
    App(application.graph, fixtureMap = fixtureMap)
}
