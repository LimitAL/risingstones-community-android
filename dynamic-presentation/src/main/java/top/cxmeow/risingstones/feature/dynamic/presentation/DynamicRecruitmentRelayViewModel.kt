package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cxmeow.risingstones.feature.dynamic.domain.*

data class DynamicRecruitmentRelayUiState(
    val sourceTitle: String?,
    val visibility: DynamicVisibility = DynamicVisibility.Public,
    val isSubmitting: Boolean = false,
    val usable: Boolean = true,
    val error: DynamicPublishingError? = null,
    val completedId: Long? = null,
)

/** One visibility confirmation, tied to the credential present when the destination opens. */
class DynamicRecruitmentRelayViewModel(
    private val actions: DynamicActionService,
    private val relay: DynamicRecruitmentRelayService,
    private val recruitmentId: Int,
    private val origin: DynamicOrigin,
    sourceTitle: String,
) : ViewModel() {
    init { DynamicRecruitmentRelayDraft(recruitmentId, origin) }
    private val mutableState = MutableStateFlow(DynamicRecruitmentRelayUiState(sourceTitle))
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var generation = 0L
    private var scope: DynamicActionScope? = null
    private val mutex = Mutex()
    private val jobs = mutableSetOf<Job>()
    private var nextResultId = 0L
    val canRelay get() = actions.canPerformAuthenticatedWrites || actions.canAttemptAuthenticatedWrites
    private val editable get() = state.value.usable && !state.value.isSubmitting && state.value.completedId == null

    fun initialize() {
        if (initialized) return
        initialized = true
        if (!canRelay) clearProtectedContent(DynamicPublishingError.Unavailable)
        else launchScoped { _, _ -> Unit }
    }

    fun synchronizeAccess() {
        if (!canRelay && state.value.usable) clearProtectedContent(DynamicPublishingError.AuthenticationRequired)
    }

    fun setVisibility(value: DynamicVisibility) {
        if (editable) mutableState.update { it.copy(visibility = value, error = null) }
    }

    fun submit() {
        if (!editable || !canRelay) return
        val visibility = state.value.visibility
        mutableState.update { it.copy(isSubmitting = true, error = null) }
        launchScoped { token, current ->
            relay.relayRecruitment(current, DynamicRecruitmentRelayDraft(recruitmentId, origin, visibility))
            requireCurrent(token, current)
            mutableState.value = DynamicRecruitmentRelayUiState(null, completedId = ++nextResultId)
        }
    }

    fun requestClose(): Boolean {
        if (state.value.isSubmitting || state.value.completedId != null) return false
        clearProtectedContent()
        return true
    }

    fun takePublishedResult(id: Long): Boolean {
        if (state.value.completedId != id) return false
        mutableState.update { it.copy(completedId = null, usable = false) }
        scope?.close()
        scope = null
        return true
    }

    fun clearProtectedContent(error: DynamicPublishingError? = null) {
        generation++
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        scope?.close()
        scope = null
        mutableState.value = DynamicRecruitmentRelayUiState(null, usable = false, error = error)
    }

    private suspend fun captureScope(token: Long): DynamicActionScope = mutex.withLock {
        if (token != generation) throw CancellationException()
        if (!canRelay) throw DynamicException.AuthenticationRequired
        scope?.let { requireCurrent(token, it); return@withLock it }
        val created = actions.beginActionScope()
        if (token != generation) {
            created.close()
            throw CancellationException()
        }
        if (!canRelay) {
            created.close()
            throw DynamicException.AuthenticationRequired
        }
        scope = created
        requireCurrent(token, created)
        created
    }

    private suspend fun requireCurrent(token: Long, current: DynamicActionScope) {
        currentCoroutineContext().ensureActive()
        if (token != generation) throw CancellationException()
        if (!canRelay || scope !== current || !current.isCurrent()) throw DynamicException.AuthenticationRequired
    }

    private fun launchScoped(block: suspend (Long, DynamicActionScope) -> Unit) {
        val token = generation
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(token, captureScope(token))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token == generation) {
                    if (error == DynamicException.AuthenticationRequired || error == DynamicException.IdentityConflict) {
                        clearProtectedContent(DynamicPublishingError.AuthenticationRequired)
                    } else mutableState.update { it.copy(isSubmitting = false, error = when (error) {
                        DynamicException.Unavailable, DynamicException.ActionNotEligible -> DynamicPublishingError.Unavailable
                        else -> DynamicPublishingError.Failed
                    }) }
                }
            } finally { jobs -= job }
        }
        jobs += job
        job.start()
    }

    override fun onCleared() { clearProtectedContent(); super.onCleared() }
}
