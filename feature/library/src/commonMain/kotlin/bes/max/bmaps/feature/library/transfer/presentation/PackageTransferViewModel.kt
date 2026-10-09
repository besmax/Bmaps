/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.library.transfer.presentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.sharing.*
import bes.max.bmaps.domain.mapbuilder.*
import bmaps.feature.library.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

data class TransferState(val busy: Boolean = false)
sealed interface TransferEvent {
    data class Message(val message: StringResource) : TransferEvent
    data class Share(val document: SharedDocument) : TransferEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PackageTransferViewModel(private val transfer: PackageTransfer, private val files: SharedDocumentStore) : ViewModel() {
    private val mutableState = MutableStateFlow(TransferState())
     val state = mutableState.asStateFlow()
    private val channel = Channel<TransferEvent>(Channel.BUFFERED)
     val events = channel.receiveAsFlow()
    private var work: Job? = null

    fun cancel() { work?.cancel() }
    fun nativeFailure() { channel.trySend(TransferEvent.Message(Res.string.transfer_failed)) }

    fun importFile(document: PickedDocument) {
        if (state.value.busy) { document.dispose(); return }
        val extension = document.name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("bmaps", "mbtiles")) {
            document.dispose()
            channel.trySend(TransferEvent.Message(Res.string.transfer_unsupported))
            return
        }
        val name = document.name.substringBeforeLast('.').trim().take(120)
        launch {
            val displayName = name.ifBlank { getString(Res.string.transfer_default_name) }
            val result = withContext(Dispatchers.IO) {
                try {
                    document.open().use { source ->
                        if (extension == "bmaps") transfer.importPackage(source) else transfer.importMbTiles(source, displayName)
                    }
                } finally { document.dispose() }
            }
            when (result) {
                is PackageResult.Success -> channel.send(TransferEvent.Message(Res.string.transfer_imported))
                is PackageResult.Failure -> channel.send(TransferEvent.Message(message(result.reason)))
            }
        }
        work?.invokeOnCompletion { document.dispose() }
    }

    fun export(id: PackageId) = launch {
        var failure: PackageFailure? = null
        try {
            val document = files.create("bmaps", "application/octet-stream") { sink ->
                when (val result = transfer.exportPackage(id, sink)) {
                    is PackageResult.Success -> Unit
                    is PackageResult.Failure -> { failure = result.reason; error("Export failed") }
                }
            }
            channel.send(TransferEvent.Share(document))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { channel.send(TransferEvent.Message(failure?.let(::message) ?: Res.string.transfer_failed)) }
    }

    private fun launch(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = TransferState(busy = true)
        work = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channel.send(TransferEvent.Message(Res.string.transfer_failed)) }
            finally { mutableState.value = TransferState() }
        }
    }

    private fun message(failure: PackageFailure): StringResource = when (failure) {
        is PackageFailure.UnsupportedVersion -> Res.string.transfer_version
        PackageFailure.UnsupportedContent, PackageFailure.UnsupportedCoordinateSystem -> Res.string.transfer_unsupported
        PackageFailure.CorruptData -> Res.string.transfer_corrupt
        is PackageFailure.SizeLimitExceeded, is PackageFailure.InsufficientStorage -> Res.string.transfer_storage
        else -> Res.string.transfer_failed
    }
}
