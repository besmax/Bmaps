package bes.max.bmaps.feature.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.datastore.ThemePreference
import dev.zacsweers.metrox.viewmodel.metroViewModel

enum class ShellDestination(val route: String, val label: String, val symbol: String) {
    LIBRARY("library", "Library", "▤"),
    CONSTRUCTOR("constructor", "Build", "+"),
    VIEWER("viewer", "Viewer", "◎"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    destination: ShellDestination,
    onNavigate: (ShellDestination) -> Unit,
    onPreferences: () -> Unit,
    fullScreen: Boolean = false,
    viewModel: ShellViewModel = metroViewModel(),
    content: @Composable ((ShellDestination) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigate by rememberUpdatedState(onNavigate)
    val openPreferences by rememberUpdatedState(onPreferences)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { event ->
                when (event) {
                    is ShellEvent.Navigate -> navigate(event.destination)
                    ShellEvent.OpenPreferences -> openPreferences()
                }
            }
        }
    }
    val darkTheme = when (state.preferences?.theme ?: ThemePreference.SYSTEM) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        Scaffold(
            contentWindowInsets = if (fullScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!fullScreen) TopAppBar(
                    title = { Text("Bmaps") },
                    actions = { TextButton(onClick = viewModel::openPreferences) { Text("Preferences") } },
                )
            },
            bottomBar = {
                if (!fullScreen) NavigationBar {
                    ShellDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { viewModel.navigateTo(item) },
                            icon = { Text(item.symbol, modifier = Modifier.clearAndSetSemantics { }) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!fullScreen && state.preferencesUnavailable) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Preferences could not be loaded.", modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::retryPreferences) { Text("Retry") }
                    }
                }
                if (!fullScreen && state.preferences == null && !state.preferencesUnavailable) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                content(viewModel::navigateTo)
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF26634A),
    secondary = Color(0xFF53645B),
    background = Color(0xFFF7FAF6),
    surface = Color(0xFFF7FAF6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93D5AE),
    secondary = Color(0xFFB7CCBC),
    background = Color(0xFF101511),
    surface = Color(0xFF101511),
)
