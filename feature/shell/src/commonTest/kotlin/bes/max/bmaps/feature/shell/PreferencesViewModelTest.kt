package bes.max.bmaps.feature.shell

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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
    fun saveIsSingleAndBufferedUntilTheScreenResumes() = runTest {
        val repository = FakePreferences()
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.selectTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.SYSTEM, repository.current.value.theme)
        viewModel.save()
        viewModel.save()
        runCurrent()
        assertEquals(1, repository.writes)
        assertEquals(ThemePreference.DARK, repository.current.value.theme)
        val received = mutableListOf<PreferencesEvent>()
        val firstCollector = launch { viewModel.events.take(1).toList(received) }
        runCurrent()
        firstCollector.join()
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.Saved), received)
        val restartedCollector = launch { viewModel.events.toList(received) }
        runCurrent()
        assertEquals(1, received.size)
        restartedCollector.cancel()
    }

    @Test
    fun saveFailureKeepsDraftAndRetrySucceeds() = runTest {
        val repository = FakePreferences().apply { failSave = true }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.selectTheme(ThemePreference.DARK)
        viewModel.save()
        runCurrent()
        assertEquals(PreferencesError.SAVE, viewModel.state.value.error)
        assertFalse(viewModel.state.value.isSaving)
        assertEquals(ThemePreference.DARK, viewModel.state.value.selectedTheme)
        assertEquals(ThemePreference.SYSTEM, repository.current.value.theme)
        val received = mutableListOf<PreferencesEvent>()
        val collector = launch { viewModel.events.toList(received) }
        runCurrent()
        assertTrue(received.isEmpty())
        repository.failSave = false
        viewModel.save()
        runCurrent()
        assertEquals(listOf<PreferencesEvent>(PreferencesEvent.Saved), received)
        collector.cancel()
    }

    @Test
    fun dismissedDraftDoesNotLeakIntoANewDialog() = runTest {
        val repository = FakePreferences()
        val first = retain(PreferencesViewModel(repository))
        runCurrent()
        first.selectTheme(ThemePreference.DARK)
        stores.first().clear()
        val second = retain(PreferencesViewModel(repository))
        runCurrent()
        assertEquals(ThemePreference.SYSTEM, second.state.value.selectedTheme)
        assertEquals(0, repository.writes)
    }

    @Test
    fun failedLoadCannotOverwritePreferencesAndCanBeRetried() = runTest {
        val repository = FakePreferences().apply { failRead = true }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        assertEquals(PreferencesError.LOAD, viewModel.state.value.error)
        viewModel.save()
        runCurrent()
        assertEquals(0, repository.writes)
        repository.failRead = false
        repository.current.value = UserPreferences(ThemePreference.DARK)
        viewModel.loadPreferences()
        runCurrent()
        assertEquals(ThemePreference.DARK, viewModel.state.value.selectedTheme)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun clearingDialogOwnerCancelsSuspendedSave() = runTest {
        var cancelled = false
        val repository = FakePreferences().apply {
            beforeSave = { try { awaitCancellation() } finally { cancelled = true } }
        }
        val viewModel = retain(PreferencesViewModel(repository))
        runCurrent()
        viewModel.save()
        runCurrent()
        stores.single().clear()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, repository.writes)
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
    var failSave = false
    var writes = 0
    var beforeSave: suspend () -> Unit = {}
    override val preferences: Flow<UserPreferences> = flow {
        if (failRead) error("Read failed")
        emitAll(current)
    }
    override suspend fun setTheme(theme: ThemePreference) {
        beforeSave()
        if (failSave) error("Write failed")
        writes++
        current.value = current.value.copy(theme = theme)
    }
    override suspend fun setDefaultCoordinateSystem(identifier: String) {
        current.value = current.value.copy(defaultCoordinateSystem = identifier)
    }
}
