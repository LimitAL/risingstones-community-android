package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import java.util.UUID

internal data class ForumCommentImageReadState(
    val isReading: Boolean = false,
    val failure: ForumImageReadFailure? = null,
)

/** Keeps the local read alive across configuration changes; no upload occurs here. */
internal class ForumCommentImageImportViewModel : ViewModel() {
    val registrationKey = "forum-comment-picker-${UUID.randomUUID()}"
    private val mutableState = MutableStateFlow(ForumCommentImageReadState())
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null
    private var readGeneration = 0L
    private var activeDraftRevision: Long? = null
    private var pickerDraftRevision: Long? = null

    fun readImage(model: OfficialForumDetailViewModel,
        read: suspend () -> OfficialForumCommentImageUpload) {
        model.synchronizeActionEligibility()
        if (mutableState.value.isReading || !model.canAttachCommentImage) return
        val generation = ++readGeneration
        val draftRevision = model.commentDraftRevision
        activeDraftRevision = draftRevision
        mutableState.value = ForumCommentImageReadState(isReading = true)
        readJob = viewModelScope.launch {
            try {
                val image = read()
                currentCoroutineContext().ensureActive()
                if (generation != readGeneration || draftRevision != model.commentDraftRevision ||
                    !model.canAttachCommentImage) return@launch
                model.setCommentImage(image)
                mutableState.value = ForumCommentImageReadState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == readGeneration && draftRevision == model.commentDraftRevision &&
                    model.canAttachCommentImage) {
                    mutableState.value = ForumCommentImageReadState(failure =
                        (error as? ForumImageReadException)?.failure ?: ForumImageReadFailure.Unreadable)
                }
            } finally {
                if (generation == readGeneration) mutableState.value = mutableState.value.copy(isReading = false)
            }
        }
    }

    fun beginPicker(model: OfficialForumDetailViewModel): Boolean {
        model.synchronizeActionEligibility()
        if (!model.canAttachCommentImage || mutableState.value.isReading) return false
        pickerDraftRevision = model.commentDraftRevision
        return true
    }

    fun acceptPickerResult(model: OfficialForumDetailViewModel): Boolean {
        val revision = pickerDraftRevision
        pickerDraftRevision = null
        return revision != null && revision == model.commentDraftRevision && model.canAttachCommentImage
    }

    fun synchronizeDraft(revision: Long) {
        if (activeDraftRevision != null && activeDraftRevision != revision ||
            pickerDraftRevision != null && pickerDraftRevision != revision) cancelRead()
    }

    fun cancelRead() {
        ++readGeneration
        readJob?.cancel()
        readJob = null
        activeDraftRevision = null
        pickerDraftRevision = null
        mutableState.value = ForumCommentImageReadState()
    }

    override fun onCleared() {
        cancelRead()
        super.onCleared()
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(failure = null)
    }
}
