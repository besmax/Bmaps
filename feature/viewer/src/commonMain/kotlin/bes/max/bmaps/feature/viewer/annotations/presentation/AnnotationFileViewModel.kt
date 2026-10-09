/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.feature.viewer.annotations.presentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.sharing.*
import bes.max.bmaps.domain.mapbuilder.AnnotationGeoJson
import bes.max.bmaps.domain.mapbuilder.AnnotationImport
import bmaps.feature.viewer.generated.resources.*
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.*
import org.jetbrains.compose.resources.StringResource

internal data class AnnotationFileState(val busy: Boolean = false, val error: StringResource? = null)
internal sealed interface AnnotationFileEvent {
    data class Loaded(val text: String) : AnnotationFileEvent
    data class Share(val document: SharedDocument) : AnnotationFileEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AnnotationFileViewModel(private val files: SharedDocumentStore) : ViewModel() {
    private val mutableState = MutableStateFlow(AnnotationFileState())
    internal val state = mutableState.asStateFlow()
    private val channel = Channel<AnnotationFileEvent>(Channel.BUFFERED)
    internal val events = channel.receiveAsFlow()
    private var work: Job? = null

    fun cancel() { work?.cancel() }
    fun nativeFailure() { mutableState.update { it.copy(error = Res.string.annotations_file_error) } }

    fun pick(document: PickedDocument) {
        if (state.value.busy) { document.dispose(); return }
        launch { read(document) }
        work?.invokeOnCompletion { document.dispose() }
    }

    private suspend fun read(document: PickedDocument) {
        val text = withContext(Dispatchers.IO) {
            try { document.open().use { source ->
                val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.readAtMostTo(buffer, 65_536)
                    if (count == -1L) break
                    require(count > 0 && buffer.size <= AnnotationGeoJson.MAX_BYTES)
                }
                buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
            } } finally { document.dispose() }
        }
        withContext(Dispatchers.Default) { AnnotationImport.decode(text) }
        channel.send(AnnotationFileEvent.Loaded(text))
    }

    fun share(text: String) = launch {
        val bytes = text.encodeToByteArray()
        require(bytes.size <= AnnotationGeoJson.MAX_BYTES)
        val document = files.create("geojson", "application/geo+json") { sink ->
            val buffer = Buffer().apply { write(bytes) }
            sink.write(buffer, bytes.size.toLong())
        }
        channel.send(AnnotationFileEvent.Share(document))
    }

    private fun launch(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = AnnotationFileState(busy = true)
        work = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { nativeFailure() }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
