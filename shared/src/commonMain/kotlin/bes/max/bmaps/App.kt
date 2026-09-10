package bes.max.bmaps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.savedstate.read
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import bes.max.bmaps.feature.constructor.FullScreenMap
import bes.max.bmaps.feature.constructor.MapSaveSettingsContent
import bes.max.bmaps.feature.constructor.ConstructorScreen
import bes.max.bmaps.feature.constructor.ProviderCredentialsContent
import bes.max.bmaps.feature.library.LibraryScreen
import bes.max.bmaps.feature.shell.AppShell
import bes.max.bmaps.feature.shell.PreferencesContent
import bes.max.bmaps.feature.shell.ShellDestination
import bes.max.bmaps.feature.viewer.ViewerScreen
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

@Composable
internal fun App(graph: AppGraph, previewMap: Boolean = false, onlineMap: Boolean = false, fixtureMap: Boolean = false) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
        val navigation = rememberNavController()
        val startDestination = if (previewMap || onlineMap) ShellDestination.CONSTRUCTOR else ShellDestination.LIBRARY
        val entry by navigation.currentBackStackEntryAsState()
        val route = if (entry?.destination?.route == PreferencesRoute || entry?.destination?.route == "provider-credentials/{identifier}") {
            navigation.previousBackStackEntry?.destination?.route
        } else {
            entry?.destination?.route
        }
        AppShell(
            destination = ShellDestination.entries.firstOrNull { it.route == route } ?: ShellDestination.LIBRARY,
            fullScreen = route == MapRoute || route == MapSettingsRoute,
            onNavigate = navigation::openDestination,
            onPreferences = { navigation.navigate(PreferencesRoute) { launchSingleTop = true } },
        ) { navigate ->
            NavHost(navController = navigation, startDestination = startDestination.route) {
                composable(ShellDestination.LIBRARY.route) {
                    LibraryScreen(onBuildMap = { navigate(ShellDestination.CONSTRUCTOR) })
                }
                composable(ShellDestination.CONSTRUCTOR.route) {
                    ConstructorScreen(onOpenLibrary = { navigate(ShellDestination.LIBRARY) },
                        onCredentials = { navigation.navigate("provider-credentials/$it") },
                        onOpenMap = { provider, style -> navigation.navigate("map/$provider/$style") })
                }
                composable(MapRoute) { mapEntry ->
                    val provider = mapEntry.arguments?.read { getString("provider") }.orEmpty()
                    val style = mapEntry.arguments?.read { getString("style") }.orEmpty()
                    FullScreenMap(provider, style, previewMap || fixtureMap,
                        onBack = { navigation.popBackStack() },
                        onSettings = { navigation.navigate(MapSettingsRoute) { launchSingleTop = true } },
                        onCredentials = { navigation.navigate("provider-credentials/$it") })
                }
                dialog(MapSettingsRoute) {
                    val mapEntry = remember(it) { navigation.getBackStackEntry(MapRoute) }
                    MapSaveSettingsContent(mapEntry) { navigation.popBackStack() }
                }
                composable(ShellDestination.VIEWER.route) {
                    ViewerScreen(onOpenLibrary = { navigate(ShellDestination.LIBRARY) })
                }
                dialog("provider-credentials/{identifier}") { entry ->
                    val identifier = entry.arguments?.read { getString("identifier") }
                    if (identifier != null) ProviderCredentialsContent(identifier) { navigation.popBackStack() }
                }
                dialog(PreferencesRoute) {
                    PreferencesContent(onDismiss = { navigation.popBackStack() })
                }
            }
        }
    }
}

private fun NavHostController.openDestination(destination: ShellDestination) {
    navigate(destination.route) {
        popUpTo(ShellDestination.LIBRARY.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private const val PreferencesRoute = "preferences"

private const val MapRoute = "map/{provider}/{style}"
private const val MapSettingsRoute = "map-settings"
