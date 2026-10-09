/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.shell.settings.presentation

import bes.max.bmaps.feature.shell.navigation.presentation.ShellEvent
import bes.max.bmaps.feature.shell.navigation.presentation.ShellViewModel
import bes.max.bmaps.feature.shell.navigation.ui.ShellDestination
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import bes.max.bmaps.core.datastore.ThemePreference
import bes.max.bmaps.core.datastore.UserPreferences
import bes.max.bmaps.core.datastore.UserPreferencesRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import bes.max.bmaps.core.datastore.CoordinateFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @BeforeTest
    fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @AfterTest
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun eachSettingAppliesImmediatelyWithoutOverwritingOtherKeys() = runTest {
        val repository = FakePreferences()
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.selectTheme(ThemePreference.DARK)
        viewModel.clusterMapObjects(false)
        viewModel.selectCoordinateFormat(CoordinateFormat.DEGREES_MINUTES_SECONDS)
        viewModel.selectCoordinateSystem("EPSG:9475")
        runCurrent()
        assertEquals(UserPreferences(ThemePreference.DARK, "EPSG:9475", false,
            CoordinateFormat.DEGREES_MINUTES_SECONDS), repository.current.value)
        assertEquals(4, repository.writes)
        assertTrue(viewModel.state.value.saving.isEmpty())
    }

    @Test
    fun failedSettingRollsBackWithoutUndoingAnotherChangeAndCanBeRetried() = runTest {
        val repository = FakePreferences().apply { failSetting = PreferenceSetting.THEME }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.selectTheme(ThemePreference.DARK)
        viewModel.clusterMapObjects(false)
        runCurrent()
        assertEquals(setOf(PreferenceSetting.THEME), viewModel.state.value.saveErrors)
        assertEquals(ThemePreference.SYSTEM, viewModel.state.value.selectedTheme)
        assertFalse(viewModel.state.value.clusterMapObjects)
        assertFalse(repository.current.value.clusterMapObjects)
        repository.failSetting = null
        viewModel.selectTheme(ThemePreference.DARK)
        runCurrent()
        assertEquals(ThemePreference.DARK, repository.current.value.theme)
        assertTrue(viewModel.state.value.saveErrors.isEmpty())
    }

    @Test
    fun pendingThemeDoesNotBlockOtherSettingsOrDuplicateWrites() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakePreferences().apply { beforeSave = { if (it == PreferenceSetting.THEME) gate.await() } }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.selectTheme(ThemePreference.DARK)
        viewModel.selectTheme(ThemePreference.LIGHT)
        viewModel.clusterMapObjects(false)
        runCurrent()
        assertEquals(setOf(PreferenceSetting.THEME), viewModel.state.value.saving)
        assertEquals(ThemePreference.DARK, viewModel.state.value.selectedTheme)
        assertFalse(repository.current.value.clusterMapObjects)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, repository.writes)
        assertEquals(ThemePreference.DARK, repository.current.value.theme)
        assertFalse(repository.current.value.clusterMapObjects)
    }

    @Test
    fun acceptedWriteFinishesAfterBackAndReopenedScreenObservesIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakePreferences().apply { beforeSave = { gate.await() } }
        val first = retain(PreferencesViewModel(repository))
        runCurrent()
        first.selectTheme(ThemePreference.DARK)
        stores.first().clear()
        val reopened = retain(PreferencesViewModel(repository))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(ThemePreference.DARK, reopened.state.value.selectedTheme)
        assertEquals(1, repository.writes)
    }

    @Test
    fun failedLoadCannotOverwriteSettingsAndCanBeRetried() = runTest {
        val repository = FakePreferences().apply { failRead = true }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        assertTrue(viewModel.state.value.loadFailed)
        viewModel.selectTheme(ThemePreference.DARK)
        viewModel.clusterMapObjects(false)
        runCurrent()
        assertEquals(0, repository.writes)
        repository.failRead = false
        repository.current.value = UserPreferences(ThemePreference.DARK)
        viewModel.loadPreferences()
        runCurrent()
        assertEquals(ThemePreference.DARK, viewModel.state.value.selectedTheme)
        assertFalse(viewModel.state.value.loadFailed)
    }

    @Test
    fun appearanceChangePreservesUnknownCrsAndInvalidSelectionIsRejected() = runTest {
        val repository = FakePreferences().apply { current.value = UserPreferences(defaultCoordinateSystem = "local:custom") }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.selectTheme(ThemePreference.DARK)
        viewModel.selectCoordinateSystem("invalid")
        runCurrent()
        assertEquals("local:custom", repository.current.value.defaultCoordinateSystem)
        assertEquals(1, repository.writes)
    }

    @Test
    fun navigationEventsAreDeliveredOnceAcrossCollectorRestart() = runTest {
        val viewModel = retain(ShellViewModel(FakePreferences()))
        runCurrent()
        viewModel.navigateTo(ShellDestination.VIEWER)
        runCurrent()
        val received = mutableListOf<ShellEvent>()
        val firstCollector = launch { viewModel.events.take(1).toList(received) }
        runCurrent()
        firstCollector.join()
        val restartedCollector = launch { viewModel.events.toList(received) }
        runCurrent()
        assertEquals(listOf<ShellEvent>(ShellEvent.Navigate(ShellDestination.VIEWER)), received)
        restartedCollector.cancel()
    }

    @Test
    fun shellRecoversFromReadFailureAndObservesThemeUpdates() = runTest {
        val repository = FakePreferences().apply { failRead = true }
        val viewModel = retain(ShellViewModel(repository))
        runCurrent()
        assertTrue(viewModel.state.value.preferencesUnavailable)
        repository.failRead = false
        viewModel.retryPreferences()
        runCurrent()
        assertFalse(viewModel.state.value.preferencesUnavailable)
        repository.setTheme(ThemePreference.DARK)
        runCurrent()
        assertEquals(ThemePreference.DARK, viewModel.state.value.preferences?.theme)
    }

    private fun <T : ViewModel> retain(viewModel: T): T {
        stores += ViewModelStore().apply { put("test", viewModel) }
        return viewModel
    }
}

private class FakePreferences : UserPreferencesRepository {
    val current = MutableStateFlow(UserPreferences())
    var failRead = false
    var failSetting: PreferenceSetting? = null
    var writes = 0
    var beforeSave: suspend (PreferenceSetting) -> Unit = {}
    override val preferences: Flow<UserPreferences> = flow {
        if (failRead) error("Read failed")
        emitAll(current)
    }
    private suspend fun change(setting: PreferenceSetting, update: (UserPreferences) -> UserPreferences) {
        beforeSave(setting)
        if (failSetting == setting) error("Write failed")
        writes++
        current.update(update)
    }
    override suspend fun setTheme(theme: ThemePreference) = change(PreferenceSetting.THEME) { it.copy(theme = theme) }
    override suspend fun setClusterMapObjects(enabled: Boolean) = change(PreferenceSetting.CLUSTERING) { it.copy(clusterMapObjects = enabled) }
    override suspend fun setCoordinateFormat(format: CoordinateFormat) = change(PreferenceSetting.COORDINATE_FORMAT) { it.copy(coordinateFormat = format) }
    override suspend fun setDefaultCoordinateSystem(identifier: String) = change(PreferenceSetting.COORDINATE_SYSTEM) { it.copy(defaultCoordinateSystem = identifier) }
    override suspend fun setDisplayPreferences(theme: ThemePreference, clusterMapObjects: Boolean,
        coordinateFormat: CoordinateFormat, defaultCoordinateSystem: String) {
        error("Settings must persist individual fields")
    }
}
