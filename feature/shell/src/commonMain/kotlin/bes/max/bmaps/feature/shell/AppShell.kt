package bes.max.bmaps.feature.shell

import bmaps.feature.shell.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import bes.max.bmaps.core.datastore.ThemePreference
import dev.zacsweers.metrox.viewmodel.metroViewModel

enum class ShellDestination(val route: String, val label: StringResource, val icon: DrawableResource) {
    LIBRARY("library", Res.string.library, Res.drawable.ic_library),
    CONSTRUCTOR("constructor", Res.string.build, Res.drawable.ic_build),
    VIEWER("viewer", Res.string.viewer, Res.drawable.ic_viewer),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    destination: ShellDestination,
    onNavigate: (ShellDestination) -> Unit,
    onPreferences: () -> Unit,
    onBack: () -> Unit = {},
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
    BmapsTheme(darkTheme) {
        Scaffold(
            contentWindowInsets = if (fullScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!fullScreen) TopAppBar(
                    navigationIcon = { if (destination != ShellDestination.LIBRARY) IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.ic_back), stringResource(Res.string.back)) } },
                    title = { Text(stringResource(Res.string.bmaps), style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = { IconButton(onClick = viewModel::openPreferences) { Icon(painterResource(Res.drawable.ic_settings), stringResource(Res.string.preferences)) } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!fullScreen && state.preferencesUnavailable) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(Res.string.preferences_could_not_be_loaded), modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::retryPreferences) { Text(stringResource(Res.string.retry)) }
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
