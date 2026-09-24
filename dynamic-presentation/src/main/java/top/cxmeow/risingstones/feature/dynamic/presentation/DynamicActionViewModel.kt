package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cxmeow.risingstones.feature.dynamic.domain.*

enum class DynamicActionError { AuthenticationRequired, Unavailable, InvalidInput, UploadFailed, Failed, InvalidImage }
data class DynamicReplyTarget(val parentId: Int, val rootParentId: Int, val authorName: String?) {
    init { require(parentId > 0 && rootParentId > 0) }
}
fun interface DynamicCommentImageSource { suspend fun read(): DynamicImageUploadInput }

data class DynamicActionUiState(
    val dynamicId: Int? = null, val editorOpen: Boolean = false,
    val editor: DynamicCommentEditorState = DynamicCommentEditorState(), val replyTarget: DynamicReplyTarget? = null,
    val image: DynamicImageUploadInput? = null, val isReadingImage: Boolean = false,
    val isLiking: Boolean = false, val isSubmitting: Boolean = false,
    val deletingCommentIds: Set<Int> = emptySet(), val isDeletingDynamic: Boolean = false,
    val likeResult: DynamicLikeResult? = null, val deleteDynamicEligible: Boolean = false,
    val deleteComments: Set<Int> = emptySet(), val confirmDeleteCommentId: Int? = null,
    val confirmDeleteDynamic: Boolean = false, val mentionCandidates: List<DynamicCommentMention> = emptyList(),
    val mentionPage: Int = 0, val mentionHasMore: Boolean = false, val isLoadingMentions: Boolean = false,
    val error: DynamicActionError? = null, val commentSuccessRevision: Long = 0,
    val deleteCommentRevision: Long = 0, val deleteDynamicRevision: Long = 0,
    val likeRevision: Long = 0, val draftRevision: Long = 0,
    val pendingEvents: List<DynamicActionEventEnvelope> = emptyList(),
)

sealed interface DynamicActionEvent {
    data class LikeChanged(val result: DynamicLikeResult) : DynamicActionEvent
    data object CommentsChanged : DynamicActionEvent
    data object DynamicDeleted : DynamicActionEvent
}
data class DynamicActionEventEnvelope(val id: Long, val dynamicId: Int, val event: DynamicActionEvent)

class DynamicActionViewModel(private val actions: DynamicActionService, private val images: DynamicImageUploadService? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(DynamicActionUiState())
    val state: StateFlow<DynamicActionUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var scope: DynamicActionScope? = null
    private var scopeCreation: Deferred<DynamicActionScope>? = null
    private val jobs = mutableSetOf<Job>()
    private var eligibilityJob: Job? = null
    private var imageJob: Job? = null
    private var mentionJob: Job? = null
    private var commentEligibilityJob: Job? = null
    private var commentEligibilityRevision = 0L
    private var uploadedImage: DynamicUploadedImage? = null
    private val scopeMutex = Mutex()
    private var imageGeneration = 0L
    private var eventId = 0L
    private val observedCommentIds = mutableSetOf<Int>()
    private var nextPickerTicket = 0L
    private var activePickerTicket: Long? = null
    val canInteract get() = actions.canPerformAuthenticatedWrites || actions.canAttemptAuthenticatedWrites
    val canUpload get() = images?.let { it.canUploadImages || it.canAttemptImageUpload } == true

    fun synchronizeAccess() {
        if (!canInteract && (state.value.dynamicId != null || scope != null || scopeCreation != null)) {
            clearProtectedContent()
        }
    }
    fun bind(dynamicId: Int?) {
        synchronizeAccess()
        if (!canInteract || dynamicId == null) {
            if (state.value.dynamicId != null) clearProtectedContent()
            return
        }
        if (state.value.dynamicId == dynamicId) return
        clearProtectedContent()
        mutableState.update { DynamicActionUiState(dynamicId = dynamicId, draftRevision = it.draftRevision) }
        val revision = generation
        eligibilityJob = viewModelScope.launch {
            try {
                val current = captureScope(revision)
                val result = actions.entryEligibility(current, dynamicId)
                if (accept(revision, current)) mutableState.update {
                    it.copy(deleteDynamicEligible = result.deleteOwnDynamic == DynamicActionEligibility.Eligible)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(revision, e)
            }
        }
    }

    fun bindCommentEligibility(ids: Collection<Int>, replyRoots: Map<Int, Int> = emptyMap()) {
        val dynamicId = state.value.dynamicId ?: return
        val expected = ids.filter { it > 0 }.toSet()
        val revision = generation
        val requestRevision = ++commentEligibilityRevision
        commentEligibilityJob?.cancel()
        commentEligibilityJob = launchScoped { current ->
            val newlyObserved = mutableSetOf<Int>()
            val missingRoots = expected.filter { it !in replyRoots && it !in observedCommentIds }.toMutableSet()
            var page = 1
            while (missingRoots.isNotEmpty()) {
                val result = actions.fetchComments(current, dynamicId, DynamicListQuery(page = page, limit = 100))
                if (!accept(revision, current)) return@launchScoped
                val before = newlyObserved.size
                result.items.mapTo(newlyObserved) { it.id }
                missingRoots.removeAll(observedCommentIds + newlyObserved)
                val nextPage = result.page + 1
                if (!result.hasMore || result.items.isEmpty() || newlyObserved.size == before || nextPage <= page) break
                page = nextPage
            }
            replyRoots.entries.groupBy({ it.value }, { it.key }).forEach { (rootId, childIds) ->
                val missing = childIds.filter { it !in observedCommentIds && it !in newlyObserved }.toMutableSet()
                var replyPage = 1
                while (missing.isNotEmpty()) {
                    val result = actions.fetchReplies(current, rootId, DynamicListQuery(page = replyPage, limit = 100))
                    if (!accept(revision, current)) return@launchScoped
                    val before = newlyObserved.size
                    result.items.mapTo(newlyObserved) { it.id }
                    missing.removeAll(observedCommentIds + newlyObserved)
                    val nextPage = result.page + 1
                    if (!result.hasMore || result.items.isEmpty() || newlyObserved.size == before || nextPage <= replyPage) break
                    replyPage = nextPage
                }
            }
            val knownEligibility = state.value.deleteComments.intersect(expected)
            val eligible = (knownEligibility + expected.intersect(observedCommentIds + newlyObserved).mapNotNull { id -> actions.commentEligibility(current, id)
                .takeIf { it.deleteOwnComment == DynamicActionEligibility.Eligible }?.commentId }.toSet()
            )
            if (requestRevision == commentEligibilityRevision && accept(revision, current)) {
                observedCommentIds += newlyObserved
                mutableState.update { it.copy(deleteComments = eligible) }
            }
        }
    }
    fun openComment() = openEditor(null)
    fun openReply(parentId: Int, rootParentId: Int, authorName: String?) = openEditor(DynamicReplyTarget(parentId, rootParentId, authorName))
    private fun openEditor(target: DynamicReplyTarget?) { if (state.value.dynamicId != null && !state.value.isSubmitting) mutableState.update { it.copy(editorOpen = true, replyTarget = target, error = null) } }
    fun cancelEditor() { if (!state.value.isSubmitting) clearDraft() }
    fun updateEditor(text: String, start: Int, end: Int, change: DynamicCommentTextChange? = null) { if (!state.value.isSubmitting) mutableState.update { it.copy(editor = it.editor.edit(text, start, end, change), error = null) } }
    fun setEmojiPicker(open: Boolean) = mutableState.update { it.copy(editor = it.editor.copy(emojiPickerOpen = open)) }
    fun insertEmoji(number: Int) { if (number in 1..46 && !state.value.isSubmitting) mutableState.update { it.copy(editor = it.editor.insert("[emo$number]").copy(emojiPickerOpen = false)) } }
    fun setMentionPicker(open: Boolean) {
        mutableState.update { it.copy(editor = it.editor.copy(mentionPickerOpen = open)) }
        if (open && state.value.mentionCandidates.isEmpty()) loadMentions(true)
    }
    fun selectMention(mention: DynamicCommentMention) { if (!state.value.isSubmitting) mutableState.update { it.copy(editor = it.editor.insert("@${mention.characterName} ", mention).copy(mentionPickerOpen = false)) } }
    fun loadMentions(reset: Boolean = false) {
        val snapshot = state.value
        if (snapshot.isLoadingMentions || (!reset && !snapshot.mentionHasMore)) return
        val revision = generation
        mentionJob?.cancel()
        mutableState.update { it.copy(isLoadingMentions = true) }
        mentionJob = viewModelScope.launch {
            try {
                val current = captureScope(revision)
                val page = actions.fetchMentionCandidates(current,
                    DynamicListQuery(if (reset) 1 else snapshot.mentionPage + 1))
                if (accept(revision, current)) mutableState.update { value ->
                    value.copy(
                        mentionCandidates = ((if (reset) emptyList() else value.mentionCandidates) + page.items)
                            .distinctBy { it.uuid },
                        mentionPage = page.page,
                        mentionHasMore = page.hasMore,
                        isLoadingMentions = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(revision, e) { copy(isLoadingMentions = false) }
            }
        }
    }
    fun selectImage(source: DynamicCommentImageSource) {
        if (!canUpload || state.value.isSubmitting) return
        val revision = generation
        val imageRevision = ++imageGeneration
        imageJob?.cancel()
        mutableState.update { it.copy(isReadingImage = true, error = null) }
        imageJob = viewModelScope.launch {
            try {
                val input = source.read()
                if (revision == generation && imageRevision == imageGeneration) {
                    uploadedImage = null
                    mutableState.update { it.copy(image = input, isReadingImage = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (revision == generation && imageRevision == imageGeneration) {
                    mutableState.update { it.copy(isReadingImage = false, error = DynamicActionError.InvalidImage) }
                }
            }
        }
    }
    fun beginImagePicker(): Long? {
        if (!canUpload || state.value.isSubmitting || state.value.dynamicId == null) return null
        return (++nextPickerTicket).also { activePickerTicket = it }
    }

    fun acceptImagePicker(ticket: Long, source: DynamicCommentImageSource) {
        if (activePickerTicket != ticket) return
        activePickerTicket = null
        selectImage(source)
    }
    fun removeImage() {
        if (!state.value.isSubmitting) {
            imageGeneration++
            activePickerTicket = null
            imageJob?.cancel()
            uploadedImage = null
            mutableState.update { it.copy(image = null, isReadingImage = false) }
        }
    }
    fun toggleLike() {
        val id = state.value.dynamicId ?: return
        if (state.value.isLiking) return
        val revision = generation
        mutableState.update { it.copy(isLiking = true, error = null) }
        launchScoped(onFailure = { copy(isLiking = false) }) { current ->
            try {
                val result = actions.toggleDynamicLike(current, id)
                if (accept(revision, current)) {
                    mutableState.update {
                        it.copy(isLiking = false, likeResult = result, likeRevision = it.likeRevision + 1)
                    }
                    enqueue(DynamicActionEvent.LikeChanged(result))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(revision, e) { copy(isLiking = false) }
            }
        }
    }
    fun submit() {
        val snapshot = state.value
        val id = snapshot.dynamicId ?: return
        if (snapshot.isSubmitting || snapshot.isReadingImage) return
        val prepared = snapshot.editor.prepareComment()
        if (prepared.html.isEmpty() && snapshot.image == null) {
            mutableState.update { it.copy(error = DynamicActionError.InvalidInput) }
            return
        }
        val revision = generation
        mutableState.update { it.copy(isSubmitting = true, error = null) }
        launchScoped(onFailure = { copy(isSubmitting = false) }) { current ->
            try {
                val uploaded = snapshot.image?.let {
                    uploadedImage ?: requireNotNull(images).uploadCommentImage(current, it).also { result ->
                        if (!accept(revision, current)) return@launchScoped
                        uploadedImage = result
                    }
                }
                actions.comment(current, DynamicCommentDraft(id, prepared.html, prepared.mentions,
                    snapshot.replyTarget?.parentId ?: 0, snapshot.replyTarget?.rootParentId ?: 0, uploaded))
                if (accept(revision, current)) {
                    uploadedImage = null
                    mutableState.update { old -> old.copy(
                        editorOpen = false, editor = DynamicCommentEditorState(), replyTarget = null,
                        image = null, isSubmitting = false, error = null,
                        commentSuccessRevision = old.commentSuccessRevision + 1,
                        draftRevision = old.draftRevision + 1,
                    ) }
                    enqueue(DynamicActionEvent.CommentsChanged)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(revision, e) { copy(isSubmitting = false) }
            }
        }
    }
    fun requestDeleteComment(id: Int) {
        if (id in state.value.deleteComments && id !in state.value.deletingCommentIds) {
            mutableState.update { it.copy(confirmDeleteCommentId = id) }
        }
    }
    fun cancelDeleteComment() = mutableState.update { it.copy(confirmDeleteCommentId = null) }
    fun confirmDeleteComment() {
        val id = state.value.confirmDeleteCommentId ?: return
        val revision = generation
        mutableState.update { it.copy(confirmDeleteCommentId = null, deletingCommentIds = it.deletingCommentIds + id) }
        launchScoped(onFailure = { copy(deletingCommentIds = deletingCommentIds - id) }) { current ->
            try {
                actions.deleteOwnComment(current, id)
                if (accept(revision, current)) {
                    mutableState.update { it.copy(deletingCommentIds = it.deletingCommentIds - id,
                        deleteComments = it.deleteComments - id,
                        deleteCommentRevision = it.deleteCommentRevision + 1) }
                    enqueue(DynamicActionEvent.CommentsChanged)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(revision, e) { copy(deletingCommentIds = deletingCommentIds - id) }
            }
        }
    }
    fun requestDeleteDynamic() {
        if (state.value.deleteDynamicEligible && !state.value.isDeletingDynamic) {
            mutableState.update { it.copy(confirmDeleteDynamic = true) }
        }
    }
    fun cancelDeleteDynamic() = mutableState.update { it.copy(confirmDeleteDynamic = false) }
    fun confirmDeleteDynamic() {
        val id = state.value.dynamicId ?: return
        if (!state.value.confirmDeleteDynamic) return
        val revision = generation
        mutableState.update { it.copy(confirmDeleteDynamic = false, isDeletingDynamic = true) }
        launchScoped(onFailure = { copy(isDeletingDynamic = false) }) { current ->
            try {
                actions.deleteOwnDynamic(current, id)
                if (accept(revision, current)) {
                    mutableState.update { it.copy(isDeletingDynamic = false,
                        deleteDynamicRevision = it.deleteDynamicRevision + 1) }
                    enqueue(DynamicActionEvent.DynamicDeleted)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fail(revision, e) { copy(isDeletingDynamic = false) }
            }
        }
    }
    fun clearError() = mutableState.update { it.copy(error = null) }
    @Synchronized
    fun takeEvent(id: Long, expectedDynamicId: Int): DynamicActionEvent? {
        var taken: DynamicActionEvent? = null
        mutableState.update { current ->
            val envelope = current.pendingEvents.firstOrNull {
                it.id == id && it.dynamicId == expectedDynamicId && current.dynamicId == expectedDynamicId
            }
            if (envelope == null) current else {
                taken = envelope.event
                current.copy(pendingEvents = current.pendingEvents.filterNot { it.id == id })
            }
        }
        return taken
    }

    fun clearProtectedContent() {
        generation++
        eligibilityJob?.cancel()
        imageJob?.cancel()
        imageGeneration++
        activePickerTicket = null
        mentionJob?.cancel()
        commentEligibilityJob?.cancel()
        commentEligibilityRevision++
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        scopeCreation?.cancel()
        scopeCreation = null
        scope?.close()
        scope = null
        uploadedImage = null
        observedCommentIds.clear()
        mutableState.value = DynamicActionUiState(draftRevision = state.value.draftRevision + 1)
    }
    private fun clearDraft() {
        imageGeneration++
        activePickerTicket = null
        imageJob?.cancel()
        uploadedImage = null
        mutableState.update { it.copy(editorOpen = false, editor = DynamicCommentEditorState(), replyTarget = null,
            image = null, isReadingImage = false, error = null, draftRevision = it.draftRevision + 1) }
    }
    private suspend fun captureScope(revision: Long): DynamicActionScope {
        if (revision != generation || !canInteract) throw DynamicException.Unavailable
        return scopeMutex.withLock {
            scope?.let { return@withLock it }
            val pending = scopeCreation ?: viewModelScope.async(start = CoroutineStart.LAZY) {
                actions.beginActionScope()
            }.also { scopeCreation = it }
            val created = try {
                pending.await()
            } catch (error: Exception) {
                scopeCreation = null
                throw error
            }
            if (revision != generation || !canInteract) {
                created.close()
                throw DynamicException.Unavailable
            }
            scope = created
            scopeCreation = null
            created
        }
    }
    private suspend fun active(revision: Long, value: DynamicActionScope) = revision == generation && value === scope && canInteract && value.isCurrent()
    private suspend fun accept(revision: Long, value: DynamicActionScope): Boolean {
        val accepted = active(revision, value)
        if (!accepted && revision == generation) clearProtectedContent()
        return accepted
    }
    private fun launchScoped(
        onFailure: DynamicActionUiState.() -> DynamicActionUiState = { this },
        block: suspend (DynamicActionScope) -> Unit,
    ): Job {
        val revision = generation
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val current = captureScope(revision)
                if (accept(revision, current)) block(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(revision, e, onFailure)
            } finally {
                jobs -= job
            }
        }
        jobs += job
        job.start()
        return job
    }
    private fun fail(
        revision: Long,
        error: Exception,
        update: DynamicActionUiState.() -> DynamicActionUiState = { this },
    ) {
        if (revision != generation) return
        val mapped = mapError(error)
        if (mapped == DynamicActionError.AuthenticationRequired || error == DynamicException.IdentityConflict) {
            clearProtectedContent()
        } else {
            mutableState.update { it.update().copy(error = mapped) }
        }
    }
    private fun mapError(error: Exception) = when (error) {
        DynamicException.AuthenticationRequired, DynamicException.IdentityConflict -> DynamicActionError.AuthenticationRequired
        DynamicException.Unavailable, DynamicException.ActionNotEligible -> DynamicActionError.Unavailable
        DynamicException.ImageUploadFailed -> DynamicActionError.UploadFailed
        is IllegalArgumentException -> DynamicActionError.InvalidInput
        else -> DynamicActionError.Failed
    }
    private fun enqueue(event: DynamicActionEvent) {
        val dynamicId = state.value.dynamicId ?: return
        val envelope = DynamicActionEventEnvelope(++eventId, dynamicId, event)
        mutableState.update { it.copy(pendingEvents = it.pendingEvents + envelope) }
    }
    override fun onCleared() {
        clearProtectedContent()
        super.onCleared()
    }
}

class DynamicActionViewModelFactory(private val actions: DynamicActionService, private val images: DynamicImageUploadService?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = DynamicActionViewModel(actions, images) as T
}
