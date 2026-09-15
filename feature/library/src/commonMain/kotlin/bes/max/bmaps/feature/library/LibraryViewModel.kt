package bes.max.bmaps.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.library.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

data class LibraryState(
    val maps: List<PackageSummary> = emptyList(), val progress: List<BuildProgress> = emptyList(),
    val loading: Boolean = true, val error: StringResource? = null, val busy: Set<PackageId> = emptySet(),
    val pages: Int = 1, val hasMore: Boolean = false,
    val search: String = "", val filter: LibraryFilter = LibraryFilter.ALL,
    val details: PackageId? = null, val deleteConfirmation: PackageId? = null,
)

enum class LibraryFilter { ALL, FAVOURITES, READY, INCOMPLETE }

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LibraryViewModel(
    private val packages: PackageRepository, private val storage: PackageBuildStorage, private val executor: DownloadExecutor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()
    private val channel = Channel<StringResource>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var listing: Job? = null

    init {
        refresh()
        viewModelScope.launch { storage.observeUnfinished().collect { result ->
            when (result) {
                is PackageResult.Success -> mutableState.update { it.copy(progress = result.value) }
                is PackageResult.Failure -> mutableState.update { it.copy(error = Res.string.library_load_failed) }
            }
        } }
    }

    fun refresh() {
        listing?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        val pageCount = state.value.pages
        val query = PackageQuery(nameContains = state.value.search, favouritesOnly = state.value.filter == LibraryFilter.FAVOURITES,
            states = when (state.value.filter) {
                LibraryFilter.READY -> setOf(PackageState.READY)
                LibraryFilter.INCOMPLETE -> PackageState.entries.toSet() - PackageState.READY
                else -> PackageState.entries.toSet()
            })
        listing = viewModelScope.launch {
            packages.observe(query).collectLatest { result ->
                when (result) {
                    is PackageResult.Success -> {
                        val maps = result.value.items.toMutableList()
                        var cursor = result.value.nextCursor
                        var failed = false
                        for (page in 1 until pageCount) {
                            val next = cursor ?: break
                            when (val more = packages.observe(query.copy(cursor = next)).first()) {
                                is PackageResult.Success -> { maps.addAll(more.value.items); cursor = more.value.nextCursor }
                                is PackageResult.Failure -> { failed = true; break }
                            }
                        }
                        mutableState.update { it.copy(maps = maps.distinctBy { map -> map.id }, hasMore = cursor != null,
                            error = if (failed) Res.string.library_load_failed else null, loading = false) }
                    }
                    is PackageResult.Failure -> mutableState.update { it.copy(error = Res.string.library_load_failed, loading = false) }
                }
            }
        }
    }

    fun search(value: String) {
        if (value == state.value.search) return
        mutableState.update { it.copy(search = value, pages = 1, maps = emptyList(), hasMore = false) }
        refresh()
    }
    fun filter(value: LibraryFilter) {
        if (value == state.value.filter) return
        mutableState.update { it.copy(filter = value, pages = 1, maps = emptyList(), hasMore = false) }
        refresh()
    }
    fun details(id: PackageId?) { mutableState.update { it.copy(details = id) } }
    fun confirmDelete(id: PackageId?) { mutableState.update { it.copy(deleteConfirmation = id) } }
    fun favourite(map: PackageSummary) = action(map.id) { packages.setFavourite(map.id, !map.favourite) }
    fun avatar(id: PackageId, avatar: MapAvatar) = action(id) { packages.setAvatar(id, avatar) }
    fun deleteConfirmed() {
        val id = state.value.deleteConfirmation ?: return
        mutableState.update { it.copy(deleteConfirmation = null, details = null) }
        action(id) {
            when (val progress = storage.observeProgress(BuildJobId(id.value)).first()) {
                is PackageResult.Success -> executor.cancel(BuildJobId(id.value), PartialPackageRetention.DELETE)
                is PackageResult.Failure -> if (progress.reason == PackageFailure.NotFound) packages.delete(id) else progress
            }
        }
    }

    fun more() { if (!state.value.loading) { mutableState.update { it.copy(pages = it.pages + 1) }; refresh() } }
    fun restore(id: PackageId) = action(id) { executor.resume(BuildJobId(id.value)) }
    fun pause(id: PackageId) = action(id) { executor.pause(BuildJobId(id.value)) }
    fun cancel(id: PackageId) = action(id) { executor.cancel(BuildJobId(id.value), PartialPackageRetention.KEEP_FOR_RESUME) }

    private fun action(id: PackageId, block: suspend () -> PackageResult<Unit>) {
        if (id in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            try {
                val result = block()
                if (result is PackageResult.Failure) channel.send(when (result.reason) {
                    PackageFailure.ProviderDownloadNotAllowed -> Res.string.library_provider_blocked
                    PackageFailure.AuthenticationRequired -> Res.string.library_credentials_required
                    else -> Res.string.library_action_failed
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channel.send(Res.string.library_action_failed) }
            finally { mutableState.update { it.copy(busy = it.busy - id) } }
        }
    }
}
