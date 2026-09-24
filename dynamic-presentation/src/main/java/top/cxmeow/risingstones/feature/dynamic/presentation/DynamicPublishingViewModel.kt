package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cxmeow.risingstones.feature.dynamic.domain.*

/** Reads one local image only when an explicit submission needs to upload it. */
fun interface DynamicPublishingImageSource {
    suspend fun read(): DynamicImageUploadInput
}

data class DynamicPublishingImage(val id: Long, val source: DynamicPublishingImageSource)
enum class DynamicPublishingError { ContentRequired, TooManyImages, InvalidImage, UploadFailed, AuthenticationRequired, Unavailable, Failed }

data class DynamicPublishingUiState(
    val relayPostId: Int? = null,
    val relayPostTitle: String? = null,
    val editor: DynamicCommentEditorState = DynamicCommentEditorState(),
    val visibility: DynamicVisibility = DynamicVisibility.Public,
    val images: List<DynamicPublishingImage> = emptyList(),
    val isSubmitting: Boolean = false,
    val uploadingImageIndex: Int? = null,
    val confirmDiscard: Boolean = false,
    val usable: Boolean = true,
    val mentionCandidates: List<DynamicCommentMention> = emptyList(),
    val mentionPage: Int = 0,
    val mentionHasMore: Boolean = false,
    val isLoadingMentions: Boolean = false,
    val error: DynamicPublishingError? = null,
    val completedId: Long? = null,
)

/** Owns one publishing draft; hosts clear its ViewModelStore when credentials change. */
class DynamicPublishingViewModel(
    private val actions: DynamicActionService,
    private val publishing: DynamicPublishingService,
    private val images: DynamicImageUploadService? = null,
    relayPostId: Int? = null,
    relayPostTitle: String? = null,
) : ViewModel() {
    init { require(relayPostId == null || relayPostId > 0) }
    private val initialState = DynamicPublishingUiState(relayPostId, relayPostTitle)
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<DynamicPublishingUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var initialized = false
    private var scope: DynamicActionScope? = null
    private val scopeMutex = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val uploaded = mutableMapOf<Long, DynamicUploadedImage>()
    private var nextImageId = 0L
    private var nextPickerTicket = 0L
    private var activePickerTicket: Long? = null
    private var nextResultId = 0L

    val canPublish get() = actions.canPerformAuthenticatedWrites || actions.canAttemptAuthenticatedWrites
    val canUpload get() = state.value.relayPostId == null && images is DynamicPublishingImageUploadService &&
        (images.canUploadImages || images.canAttemptImageUpload)
    private val editable get() = state.value.usable && !state.value.isSubmitting && state.value.completedId == null

    fun initialize() {
        if (initialized) return
        initialized = true
        if (!canPublish) {
            clearProtectedContent(DynamicPublishingError.Unavailable)
            return
        }
        launchScoped { _, _ -> Unit }
    }

    fun synchronizeAccess() {
        if (!canPublish && state.value.usable) clearProtectedContent(DynamicPublishingError.AuthenticationRequired)
    }

    fun updateEditor(text: String, start: Int, end: Int, change: DynamicCommentTextChange? = null) {
        if (editable) mutableState.update { it.copy(editor = it.editor.edit(text, start, end, change), error = null) }
    }

    fun setVisibility(value: DynamicVisibility) {
        if (editable) mutableState.update { it.copy(visibility = value) }
    }

    fun setEmojiPicker(open: Boolean) {
        if (editable) mutableState.update { it.copy(editor = it.editor.copy(emojiPickerOpen = open, mentionPickerOpen = false)) }
    }

    fun insertEmoji(number: Int) {
        if (editable && number in 1..46) mutableState.update {
            it.copy(editor = it.editor.insert("[emo$number]").copy(emojiPickerOpen = false), error = null)
        }
    }

    fun setMentionPicker(open: Boolean) {
        if (!editable) return
        mutableState.update { it.copy(editor = it.editor.copy(mentionPickerOpen = open, emojiPickerOpen = false)) }
        if (open && state.value.mentionCandidates.isEmpty()) loadMentions(reset = true)
    }

    fun selectMention(mention: DynamicCommentMention) {
        if (editable) mutableState.update {
            it.copy(editor = it.editor.insert("@${mention.characterName} ", mention).copy(mentionPickerOpen = false), error = null)
        }
    }

    fun loadMentions(reset: Boolean = false) {
        val snapshot = state.value
        if (!editable || snapshot.isLoadingMentions || (!reset && !snapshot.mentionHasMore)) return
        mutableState.update { it.copy(isLoadingMentions = true, error = null) }
        launchScoped(onFailure = { copy(isLoadingMentions = false) }) { token, current ->
            val page = actions.fetchMentionCandidates(current, DynamicListQuery(if (reset) 1 else snapshot.mentionPage + 1))
            requireCurrent(token, current)
            mutableState.update { it.copy(
                mentionCandidates = ((if (reset) emptyList() else it.mentionCandidates) + page.items).distinctBy { candidate -> candidate.uuid },
                mentionPage = page.page, mentionHasMore = page.hasMore, isLoadingMentions = false,
            ) }
        }
    }

    fun beginImagePicker(): Long? {
        if (!editable || !canUpload || state.value.images.size >= MaximumImages) return null
        return (++nextPickerTicket).also { activePickerTicket = it }
    }

    fun acceptImagePicker(ticket: Long, sources: List<DynamicPublishingImageSource>) {
        if (activePickerTicket != ticket) return
        activePickerTicket = null
        addImages(sources)
    }

    fun addImages(sources: List<DynamicPublishingImageSource>) {
        if (!editable || !canUpload || sources.isEmpty()) return
        if (state.value.images.size + sources.size > MaximumImages) {
            mutableState.update { it.copy(error = DynamicPublishingError.TooManyImages) }
            return
        }
        val additions = sources.map { DynamicPublishingImage(++nextImageId, it) }
        mutableState.update { it.copy(images = it.images + additions, error = null) }
    }

    fun removeImage(id: Long) {
        if (!editable) return
        uploaded.remove(id)
        mutableState.update { it.copy(images = it.images.filterNot { image -> image.id == id }, error = null) }
    }

    fun moveImage(id: Long, offset: Int) {
        if (!editable || offset !in setOf(-1, 1)) return
        val values = state.value.images.toMutableList()
        val from = values.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in values.indices) return
        values.add(to, values.removeAt(from))
        mutableState.update { it.copy(images = values) }
    }

    fun submit() {
        if (!editable || !canPublish) return
        val snapshot = state.value
        val prepared = snapshot.editor.preparePublishingContent()
        if (snapshot.relayPostId == null && prepared.html.isBlank()) {
            mutableState.update { it.copy(error = DynamicPublishingError.ContentRequired) }
            return
        }
        activePickerTicket = null
        mutableState.update { it.copy(isSubmitting = true, error = null, confirmDiscard = false) }
        launchScoped(onFailure = { copy(isSubmitting = false, uploadingImageIndex = null) }) { token, current ->
            if (snapshot.relayPostId == null) {
                val results = snapshot.images.mapIndexed { index, item ->
                    requireCurrent(token, current)
                    uploaded[item.id] ?: run {
                        mutableState.update { it.copy(uploadingImageIndex = index + 1) }
                        val input = try { item.source.read() }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { throw InvalidLocalImage() }
                        requireCurrent(token, current)
                        val uploader = images as? DynamicPublishingImageUploadService ?: throw DynamicException.Unavailable
                        val result = uploader.uploadPublishingImage(current, input)
                        requireCurrent(token, current)
                        uploaded[item.id] = result
                        result
                    }
                }
                requireCurrent(token, current)
                mutableState.update { it.copy(uploadingImageIndex = null) }
                publishing.publish(current, DynamicPublishDraft(prepared.html, snapshot.visibility, prepared.mentions, results))
            } else {
                publishing.relayPost(current, DynamicPostRelayDraft(snapshot.relayPostId, prepared.html, snapshot.visibility, prepared.mentions))
            }
            requireCurrent(token, current)
            uploaded.clear()
            mutableState.value = initialState.copy(completedId = ++nextResultId)
        }
    }

    /** Returns true only when there is no draft to confirm discarding. */
    fun requestClose(): Boolean {
        if (state.value.isSubmitting || state.value.completedId != null) return false
        val snapshot = state.value
        if (snapshot.editor.text.isNotBlank() || snapshot.images.isNotEmpty() || snapshot.visibility != DynamicVisibility.Public) {
            mutableState.update { it.copy(confirmDiscard = true) }
            return false
        }
        clearProtectedContent()
        return true
    }

    fun cancelDiscard() { mutableState.update { it.copy(confirmDiscard = false) } }
    fun discard() { clearProtectedContent() }
    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun takePublishedResult(id: Long): Boolean {
        if (state.value.completedId != id) return false
        mutableState.update { it.copy(completedId = null, usable = false) }
        scope?.close()
        scope = null
        return true
    }

    fun clearProtectedContent(error: DynamicPublishingError? = null) {
        generation++
        activePickerTicket = null
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        scope?.close()
        scope = null
        uploaded.clear()
        mutableState.value = initialState.copy(relayPostTitle = null, usable = false, error = error)
    }

    private suspend fun captureScope(token: Long): DynamicActionScope = scopeMutex.withLock {
        if (token != generation) throw CancellationException()
        if (!canPublish) throw DynamicException.AuthenticationRequired
        scope?.let { requireCurrent(token, it); return@withLock it }
        val created = actions.beginActionScope()
        if (token != generation) {
            created.close()
            throw CancellationException()
        }
        if (!canPublish) {
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
        if (!canPublish || scope !== current || !current.isCurrent()) throw DynamicException.AuthenticationRequired
    }

    private fun launchScoped(
        onFailure: DynamicPublishingUiState.() -> DynamicPublishingUiState = { this },
        block: suspend (Long, DynamicActionScope) -> Unit,
    ) {
        val token = generation
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val current = captureScope(token)
                block(token, current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token == generation) {
                    if (error == DynamicException.AuthenticationRequired || error == DynamicException.IdentityConflict) {
                        clearProtectedContent(DynamicPublishingError.AuthenticationRequired)
                    } else {
                        val mapped = when (error) {
                            is InvalidLocalImage -> DynamicPublishingError.InvalidImage
                            DynamicException.ImageUploadFailed -> DynamicPublishingError.UploadFailed
                            DynamicException.Unavailable, DynamicException.ActionNotEligible -> DynamicPublishingError.Unavailable
                            else -> DynamicPublishingError.Failed
                        }
                        mutableState.update { it.onFailure().copy(error = mapped) }
                    }
                }
            } finally {
                jobs -= job
            }
        }
        jobs += job
        job.start()
    }

    override fun onCleared() { clearProtectedContent(); super.onCleared() }
    private class InvalidLocalImage : Exception()
    companion object { const val MaximumImages = 9 }
}
