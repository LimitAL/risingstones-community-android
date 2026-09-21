package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.cxmeow.risingstones.feature.personaldata.domain.*

sealed interface PersonalDataShareUiState {
    data object Closed : PersonalDataShareUiState
    data object Rendering : PersonalDataShareUiState
    data class NotReady(val reason: PersonalDataShareNotReadyReason) : PersonalDataShareUiState
    data object Failed : PersonalDataShareUiState
    data object AuthenticationRequired : PersonalDataShareUiState
    data class Preview(val artifact: PersonalDataShareArtifact, val isSharing: Boolean = false, val shareFailed: Boolean = false) : PersonalDataShareUiState
}

/** A preview is a frozen source snapshot. Resizing/recomposition never regenerates the image. */
class PersonalDataShareViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<PersonalDataShareUiState>(PersonalDataShareUiState.Closed)
    val state: StateFlow<PersonalDataShareUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var renderJob: Job? = null
    private var shareJob: Job? = null
    private var cleanup: (() -> Unit)? = null

    fun open(input: PersonalDataShareInput, resources: PersonalDataShareResourceService,
        renderer: PersonalDataShareRenderer, hasAccess: () -> Boolean, clearExport: () -> Unit = {}) {
        close()
        cleanup = clearExport
        val request = generation
        if (!hasAccess()) { mutableState.value = PersonalDataShareUiState.AuthenticationRequired; return }
        mutableState.value = PersonalDataShareUiState.Rendering
        renderJob = viewModelScope.launch {
            try {
                val catalogs = resources.fetchShareCatalogs()
                ensureActive()
                if (request != generation) return@launch
                if (!hasAccess()) { reject(); return@launch }
                when (val built = PersonalDataShareBuilder.build(input, catalogs)) {
                    is PersonalDataShareBuildResult.NotReady -> {
                        if (built.reason == PersonalDataShareNotReadyReason.AuthenticationRequired) reject()
                        else mutableState.value = PersonalDataShareUiState.NotReady(built.reason)
                    }
                    is PersonalDataShareBuildResult.Ready -> {
                        val artifact = renderer.render(built.document)
                        ensureActive()
                        if (request != generation) return@launch
                        if (!hasAccess()) reject()
                        else mutableState.value = PersonalDataShareUiState.Preview(artifact)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PersonalDataException.AuthenticationRequired) { if (request == generation) reject() }
            catch (_: Exception) {
                if (request == generation) {
                    if (!hasAccess()) reject() else mutableState.value = PersonalDataShareUiState.Failed
                }
            }
        }
    }

    fun share(hasAccess: () -> Boolean, send: suspend (PersonalDataShareArtifact) -> Unit) {
        val preview = mutableState.value as? PersonalDataShareUiState.Preview ?: return
        if (preview.isSharing) return
        if (!hasAccess()) { reject(); return }
        val request = generation
        mutableState.value = preview.copy(isSharing = true, shareFailed = false)
        shareJob = viewModelScope.launch {
            try {
                send(preview.artifact)
                ensureActive()
                if (request == generation) {
                    if (!hasAccess()) reject()
                    else mutableState.value = preview.copy(isSharing = false, shareFailed = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == generation) {
                    if (!hasAccess()) reject() else mutableState.value = preview.copy(shareFailed = true)
                }
            }
        }
    }

    private fun reject() {
        close()
        mutableState.value = PersonalDataShareUiState.AuthenticationRequired
    }

    fun close() {
        generation++
        renderJob?.cancel(); renderJob = null
        shareJob?.cancel(); shareJob = null
        mutableState.value = PersonalDataShareUiState.Closed
        cleanup?.invoke(); cleanup = null
    }
    fun clearProtectedContent() = close()
    override fun onCleared() { close(); super.onCleared() }
}
