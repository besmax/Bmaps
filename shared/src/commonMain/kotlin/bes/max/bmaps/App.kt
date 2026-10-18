/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import bes.max.bmaps.domain.mapbuilder.PackageId
import androidx.navigation.NavHostController
import androidx.savedstate.read
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import bes.max.bmaps.feature.constructor.map.ui.FullScreenMap
import bes.max.bmaps.feature.constructor.download.ui.MapSaveSettingsContent
import bes.max.bmaps.feature.constructor.map.ui.ConstructorScreen
import bes.max.bmaps.feature.constructor.credentials.ui.ProviderCredentialsContent
import bes.max.bmaps.feature.library.catalog.ui.LibraryScreen
import bes.max.bmaps.feature.shell.navigation.ui.AppShell
import bes.max.bmaps.feature.shell.settings.ui.SettingsScreen
import bes.max.bmaps.feature.shell.navigation.ui.ShellDestination
import bes.max.bmaps.feature.viewer.map.ui.ViewerScreen
import bes.max.bmaps.feature.viewer.history.ui.HistoryScreen
import bes.max.bmaps.feature.viewer.history.ui.ActivityListScreen
import bes.max.bmaps.feature.viewer.history.ui.ActivityDetailsScreen
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

@Composable
internal fun App(graph: AppGraph, previewMap: Boolean = false, onlineMap: Boolean = false, fixtureMap: Boolean = false) {
    CompositionLocalProvider(LocalMetroViewModelFactory provides graph.metroViewModelFactory) {
        val navigation = rememberNavController()
        val startDestination = if (previewMap || onlineMap) ShellDestination.CONSTRUCTOR else ShellDestination.LIBRARY
        val entry by navigation.currentBackStackEntryAsState()
        val route = if (entry?.destination?.route == "provider-credentials/{identifier}") {
            navigation.previousBackStackEntry?.destination?.route
        } else {
            entry?.destination?.route
        }
        AppShell(
            destination = ShellDestination.entries.firstOrNull { it.route == route || (it == ShellDestination.VIEWER && route in ViewerRoutes) } ?: ShellDestination.LIBRARY,
            fullScreen = route == MapRoute || route == MapSettingsRoute || route in ViewerRoutes || route == HistoryRoute || route == SavedHistoryRoute || route == SettingsRoute,
            onBack = { navigation.popBackStack() },
            onNavigate = navigation::openDestination,
            onActivities = { navigation.navigate(HistoryRoute) { launchSingleTop = true } },
            onPreferences = { navigation.navigate(SettingsRoute) { launchSingleTop = true } },
        ) { navigate ->
            NavHost(navController = navigation, startDestination = startDestination.route) {
                composable(ShellDestination.LIBRARY.route) {
                    LibraryScreen(onBuildMap = { navigate(ShellDestination.CONSTRUCTOR) },
                        onCredentials = { navigation.navigate("provider-credentials/$it") },
                        onOpenMap = { navigation.navigate("viewer/${it.value}") { launchSingleTop = true } })
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
                    MapSaveSettingsContent(mapEntry,
                        onCredentials = { navigation.navigate("provider-credentials/$it") },
                        onStarted = { navigation.navigate(ShellDestination.LIBRARY.route) {
                            popUpTo(ShellDestination.LIBRARY.route)
                            launchSingleTop = true
                        } },
                        onDismiss = { navigation.popBackStack() })
                }
                composable(ViewerRoute) { viewerEntry ->
                    val id = viewerEntry.arguments?.read { getString("packageId") }.orEmpty()
                    ViewerScreen(PackageId(id), onBack = { navigation.popBackStack() },
                        onHistory = { navigation.navigate(HistoryRoute) { launchSingleTop = true } },
                        onReview = { activity -> navigation.navigate("viewer/$id/activity/$activity") },
                        onTrack = { source -> navigation.navigate(source?.let { "viewer/$it/track" } ?: TrackMapRoute) { launchSingleTop = true } })
                }
                composable(HistoryRoute) {
                    ActivityListScreen(onBack = { navigation.popBackStack() },
                        onDetails = { navigation.navigate("activity/$it") },
                        onRecord = { navigation.openDestination(ShellDestination.LIBRARY) },
                        onOpenActive = { navigation.navigate(TrackMapRoute) { launchSingleTop = true } },
                        onSavedRoutes = { navigation.navigate(SavedHistoryRoute) { launchSingleTop = true } })
                }
                composable(SavedHistoryRoute) {
                    HistoryScreen(initialRoutesTab = true, onBack = { navigation.popBackStack() },
                        onReview = { navigation.navigate("activity/$it") },
                        onRecord = { navigation.openDestination(ShellDestination.LIBRARY) },
                        onOpenActive = { navigation.navigate(TrackMapRoute) { launchSingleTop = true } },
                        onFollow = { navigation.navigate("track-map/$it") })
                }
                composable(PackageTrackRoute) { trackEntry ->
                    val map = trackEntry.arguments?.read { getString("packageId") }.orEmpty()
                    ViewerScreen(PackageId(map), onBack = { navigation.popBackStack() }, trackScreen = true,
                        onHistory = { navigation.navigate(HistoryRoute) { launchSingleTop = true } },
                        onReview = { navigation.navigate("viewer/$map/activity/$it") })
                }
                composable(TrackMapRoute) {
                    ViewerScreen(null, trackScreen = true, onBack = { navigation.popBackStack() },
                        onHistory = { navigation.navigate(HistoryRoute) { launchSingleTop = true } },
                        onReview = { navigation.navigate("activity/$it") },
                        onTrack = { source -> navigation.navigate(source?.let { "viewer/$it/track" } ?: TrackMapRoute) { launchSingleTop = true } })
                }
                composable(SavedRouteRoute) { routeEntry ->
                    val id = routeEntry.arguments?.read { getString("routeId") }.orEmpty()
                    val owner = bes.max.bmaps.domain.activity.routePackageId(id)
                    if (owner == null) LibraryScreen(onBuildMap = { navigation.openDestination(ShellDestination.CONSTRUCTOR) },
                        onCredentials = { navigation.navigate("provider-credentials/$it") },
                        onOpenMap = { navigation.navigate("viewer/${it.value}/route/$id") })
                    else ViewerScreen(PackageId(owner), onBack = { navigation.popBackStack() }, initialRouteId = id,
                        onHistory = { navigation.navigate(HistoryRoute) { launchSingleTop = true } },
                        onReview = { navigation.navigate("activity/$it") },
                        onTrack = { source -> navigation.navigate(source?.let { "viewer/$it/track" } ?: TrackMapRoute) { launchSingleTop = true } })
                }
                composable(PackageSavedRouteRoute) { routeEntry ->
                    val id = routeEntry.arguments?.read { getString("routeId") }.orEmpty()
                    val map = routeEntry.arguments?.read { getString("packageId") }.orEmpty()
                    ViewerScreen(PackageId(map), onBack = { navigation.popBackStack() }, initialRouteId = id,
                        onHistory = { navigation.navigate(HistoryRoute) { launchSingleTop = true } },
                        onReview = { navigation.navigate("viewer/$map/activity/$it") },
                        onTrack = { navigation.navigate("viewer/$map/track") { launchSingleTop = true } })
                }
                composable(ActivityReviewRoute) { reviewEntry ->
                    val id = reviewEntry.arguments?.read { getString("activityId") }.orEmpty()
                    ActivityDetailsScreen(id, onBack = { navigation.popBackStack() })
                }
                composable(PackageActivityReviewRoute) { reviewEntry ->
                    val id = reviewEntry.arguments?.read { getString("activityId") }.orEmpty()
                    val map = reviewEntry.arguments?.read { getString("packageId") }.orEmpty()
                    ActivityDetailsScreen(id, onBack = { navigation.popBackStack() }, packageId = PackageId(map))
                }
                dialog("provider-credentials/{identifier}") { entry ->
                    val identifier = entry.arguments?.read { getString("identifier") }
                    if (identifier != null) ProviderCredentialsContent(identifier) { navigation.popBackStack() }
                }
                composable(SettingsRoute) {
                    SettingsScreen(onBack = { navigation.popBackStack() })
                }
            }
        }
    }
}

private fun NavHostController.openDestination(destination: ShellDestination) {
    navigate(destination.route) {
        popUpTo(ShellDestination.LIBRARY.route)
        launchSingleTop = true
    }
}

private const val SettingsRoute = "settings"

private const val MapRoute = "map/{provider}/{style}"
private const val MapSettingsRoute = "map-settings"

private const val PackageSavedRouteRoute = "viewer/{packageId}/route/{routeId}"
private const val PackageTrackRoute = "viewer/{packageId}/track"
private const val ViewerRoute = "viewer/{packageId}"

private const val HistoryRoute = "activities"
private const val SavedHistoryRoute = "saved-routes"
private const val TrackMapRoute = "track-map"
private const val SavedRouteRoute = "track-map/{routeId}"
private const val ActivityReviewRoute = "activity/{activityId}"
private const val PackageActivityReviewRoute = "viewer/{packageId}/activity/{activityId}"
private val ViewerRoutes = setOf(ViewerRoute, PackageSavedRouteRoute, PackageTrackRoute, TrackMapRoute, SavedRouteRoute, ActivityReviewRoute, PackageActivityReviewRoute)
