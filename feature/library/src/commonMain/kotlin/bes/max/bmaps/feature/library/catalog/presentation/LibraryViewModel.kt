/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.catalog.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.domain.mapbuilder.download.BuildJobId
import bes.max.bmaps.domain.mapbuilder.download.DownloadExecutor
import bes.max.bmaps.domain.mapbuilder.download.PackageBuildStorage
import bes.max.bmaps.domain.mapbuilder.download.PartialPackageRetention
import bes.max.bmaps.domain.mapbuilder.model.MapAvatar
import bes.max.bmaps.domain.mapbuilder.model.PackageFailure
import bes.max.bmaps.domain.mapbuilder.model.PackageId
import bes.max.bmaps.domain.mapbuilder.model.PackageResult
import bes.max.bmaps.domain.mapbuilder.model.PackageState
import bes.max.bmaps.domain.mapbuilder.repository.PackageQuery
import bes.max.bmaps.domain.mapbuilder.repository.PackageRepository
import bmaps.feature.library.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
data class LibraryState(
    val maps: ImmutableList<LibraryMapUi> = persistentListOf(),
    val progress: ImmutableList<LibraryDownloadUi> = persistentListOf(),
    val loading: Boolean = true,
    val error: StringResource? = null,
    val busy: PersistentSet<String> = persistentSetOf(),
    val savingFavourites: PersistentSet<String> = persistentSetOf(),
    val pages: Int = 1,
    val hasMore: Boolean = false,
    val search: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val details: LibraryMapUi? = null,
    val deleteConfirmation: String? = null,
    val actionsExpanded: Boolean = false,
)

enum class LibraryFilter { ALL, FAVOURITES, READY, INCOMPLETE }

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LibraryViewModel(
    private val packages: PackageRepository,
    private val storage: PackageBuildStorage,
    private val executor: DownloadExecutor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()
    private val channel = Channel<StringResource>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var listing: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            storage.observeUnfinished().collect { result ->
                when (result) {
                    is PackageResult.Success -> mutableState.update {
                        it.copy(progress = result.value.map { progress -> progress.toLibraryUi() }
                            .toImmutableList())
                    }

                    is PackageResult.Failure -> mutableState.update { it.copy(error = Res.string.library_load_failed) }
                }
            }
        }
    }

    fun refresh() {
        listing?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        val pageCount = state.value.pages
        val query = PackageQuery(
            nameContains = state.value.search,
            favouritesOnly = state.value.filter == LibraryFilter.FAVOURITES,
            states = when (state.value.filter) {
                LibraryFilter.READY -> setOf(PackageState.READY)
                LibraryFilter.INCOMPLETE -> PackageState.entries.toSet() - PackageState.READY
                else -> PackageState.entries.toSet()
            }
        )
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
                                is PackageResult.Success -> {
                                    maps.addAll(more.value.items); cursor = more.value.nextCursor
                                }

                                is PackageResult.Failure -> {
                                    failed = true; break
                                }
                            }
                        }
                        mutableState.update {
                            it.copy(
                                maps = maps.distinctBy { map -> map.id }
                                .map { map -> map.toLibraryUi() }.toImmutableList(),
                                hasMore = cursor != null,
                                error = if (failed) Res.string.library_load_failed else null,
                                loading = false
                            )
                        }
                    }

                    is PackageResult.Failure -> mutableState.update {
                        it.copy(
                            error = Res.string.library_load_failed,
                            loading = false
                        )
                    }
                }
            }
        }
    }

    fun actionsExpanded(expanded: Boolean) {
        mutableState.update { it.copy(actionsExpanded = expanded) }
    }

    fun search(value: String) {
        if (value == state.value.search) return
        mutableState.update {
            it.copy(
                search = value,
                pages = 1,
                maps = persistentListOf(),
                hasMore = false
            )
        }
        refresh()
    }

    fun filter(value: LibraryFilter) {
        if (value == state.value.filter) return
        mutableState.update {
            it.copy(
                filter = value,
                pages = 1,
                maps = persistentListOf(),
                hasMore = false
            )
        }
        refresh()
    }

    fun details(id: String?) {
        mutableState.update { current ->
            current.copy(details = current.maps.firstOrNull { it.id == id })
        }
    }

    fun confirmDelete(id: String?) {
        mutableState.update { it.copy(deleteConfirmation = id) }
    }

    fun favourite(id: String) {
        val map = state.value.maps.firstOrNull { it.id == id } ?: return
        action(id, favouriteOnly = true) { packages.setFavourite(it, !map.favourite) }
    }

    fun avatar(id: String, avatar: MapAvatar) = action(id) { packages.setAvatar(it, avatar) }
    fun deleteConfirmed() {
        val id = state.value.deleteConfirmation ?: return
        mutableState.update { it.copy(deleteConfirmation = null, details = null) }
        action(id) { packageId ->
            when (val progress = storage.observeProgress(BuildJobId(id)).first()) {
                is PackageResult.Success -> executor.cancel(
                    BuildJobId(id),
                    PartialPackageRetention.DELETE
                )

                is PackageResult.Failure -> if (progress.reason == PackageFailure.NotFound) packages.delete(
                    packageId
                ) else progress
            }
        }
    }

    fun more() {
        if (!state.value.loading) {
            mutableState.update { it.copy(pages = it.pages + 1) }; refresh()
        }
    }

    fun restore(id: String) = action(id) { executor.resume(BuildJobId(id)) }
    fun pause(id: String) = action(id) { executor.pause(BuildJobId(id)) }
    fun cancel(id: String) =
        action(id) { executor.cancel(BuildJobId(id), PartialPackageRetention.KEEP_FOR_RESUME) }

    private fun action(
        id: String,
        favouriteOnly: Boolean = false,
        block: suspend (PackageId) -> PackageResult<Unit>
    ) {
        if (id in state.value.busy || (favouriteOnly && id in state.value.savingFavourites)) return
        mutableState.update {
            if (favouriteOnly) it.copy(savingFavourites = it.savingFavourites.add(id))
            else it.copy(busy = it.busy.add(id))
        }
        viewModelScope.launch {
            try {
                val result = block(PackageId(id))
                if (result is PackageResult.Failure) channel.send(
                    when (result.reason) {
                        PackageFailure.ProviderDownloadNotAllowed -> Res.string.library_provider_blocked
                        PackageFailure.AuthenticationRequired -> Res.string.library_credentials_required
                        else -> Res.string.library_action_failed
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                channel.send(Res.string.library_action_failed)
            } finally {
                mutableState.update {
                    if (favouriteOnly) it.copy(savingFavourites = it.savingFavourites.remove(id))
                    else it.copy(busy = it.busy.remove(id))
                }
            }
        }
    }
}
