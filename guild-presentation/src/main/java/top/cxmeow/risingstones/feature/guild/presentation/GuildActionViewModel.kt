package top.cxmeow.risingstones.feature.guild.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import top.cxmeow.risingstones.feature.guild.domain.GuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildActionService
import top.cxmeow.risingstones.feature.guild.domain.GuildCommentActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildGuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.domain.GuildLabel
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentDraft
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentMention
import top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult

enum class GuildActionError { AuthenticationRequired, Unavailable, InvalidInput, UploadFailed, Failed }

fun interface GuildAlbumImageSource {
    suspend fun read(): GuildImageUploadInput
}

data class GuildCommentTarget(
    val parentId: Int,
    val rootParentId: Int,
    val authorUuid: String?,
    val authorName: String?,
) {
    init {
        require(parentId >= 0 && rootParentId >= 0)
        require((parentId == 0) == (rootParentId == 0))
    }

    val isTopLevel: Boolean get() = parentId == 0

    companion object {
        val TopLevel = GuildCommentTarget(0, 0, null, null)
    }
}

data class GuildActionUiState(
    val guildId: GuildId? = null,
    val guildEligibility: GuildGuildActionEligibility? = null,
    val photoEligibility: GuildPhotoActionEligibility? = null,
    val commentEligibility: Map<Int, GuildCommentActionEligibility> = emptyMap(),
    val isLoadingEligibility: Boolean = false,
    val labels: List<GuildLabel> = emptyList(),
    val isLoadingLabels: Boolean = false,
    val isManagerOpen: Boolean = false,
    val managerDescription: String = "",
    val selectedAlbumImages: List<GuildImageUploadInput> = emptyList(),
    val selectedAlbumImageCount: Int = selectedAlbumImages.size,
    val avatarDraft: GuildImageUploadInput? = null,
    val commentTarget: GuildCommentTarget? = null,
    val commentText: String = "",
    val commentImage: GuildImageUploadInput? = null,
    val isSavingGuildInfo: Boolean = false,
    val isUploadingAlbum: Boolean = false,
    val uploadedAlbumCount: Int = 0,
    val isLikingPhoto: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val deletingCommentIds: Set<Int> = emptySet(),
    val isDeletingPhoto: Boolean = false,
    val confirmDeleteCommentId: Int? = null,
    val confirmDeletePhotoId: Int? = null,
    val likeResults: Map<Int, GuildPhotoLikeResult> = emptyMap(),
    val guildInfoSuccessRevision: Long = 0,
    val albumSuccessRevision: Long = 0,
    val commentSuccessRevision: Long = 0,
    val deletedCommentRevision: Long = 0,
    val deletedCommentId: Int? = null,
    val deletedPhotoRevision: Long = 0,
    val deletedPhotoId: Int? = null,
    val error: GuildActionError? = null,
    val draftRevision: Long = 0,
) {
    val canManageGuild: Boolean get() = guildEligibility?.manageGuild == GuildActionEligibility.Eligible
    val canUploadAlbum: Boolean get() = guildEligibility?.uploadAlbum == GuildActionEligibility.Eligible
    fun canDeleteComment(id: Int): Boolean =
        commentEligibility[id]?.deleteOwnComment == GuildActionEligibility.Eligible
    fun canDeletePhoto(id: Int): Boolean =
        photoEligibility?.photoId == id && photoEligibility.deletePhoto == GuildActionEligibility.Eligible
}

class GuildActionViewModel(
    private val actionService: GuildActionService,
    private val imageUploadService: GuildImageUploadService? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GuildActionUiState())
    val state: StateFlow<GuildActionUiState> = mutableState.asStateFlow()
    private var eligibilityJob: Job? = null
    private var eligibilityRevision = 0L
    private val writeJobs = mutableSetOf<Job>()
    private var generation = 0L
    private var cleared = false
    private var previouslyCanInteract = canInteract
    private var previouslyCanUpload = canUploadImages
    private var boundPhotoId: Int? = null
    private var boundCommentIds: Set<Int> = emptySet()
    private var albumSources: List<GuildAlbumImageSource> = emptyList()
    private val uploadedAlbumImages = mutableListOf<top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage>()
    private var resourceScope: GuildActionScope? = null
    private var uploadedAvatar: GuildUploadedImage? = null
    private var uploadedCommentImage: GuildUploadedImage? = null

    val canWrite: Boolean get() = actionService.canPerformAuthenticatedWrites
    val canAttemptWrite: Boolean get() = actionService.canAttemptAuthenticatedWrites
    val canInteract: Boolean get() = !cleared && (canWrite || canAttemptWrite)
    val canUploadImages: Boolean get() = !cleared && imageUploadService?.let {
        it.canUploadImages || it.canAttemptImageUpload
    } == true

    fun synchronizeEligibility() {
        val interact = canInteract
        val upload = canUploadImages
        val revoked = previouslyCanInteract && !interact || previouslyCanUpload && !upload
        previouslyCanInteract = interact
        previouslyCanUpload = upload
        if (revoked) clearProtectedContent()
    }

    fun bindResources(guildId: GuildId?, photoId: Int?, comments: Collection<GuildPhotoComment>) {
        synchronizeEligibility()
        val ids = comments.mapTo(linkedSetOf()) { it.id }
        if (!canInteract || guildId == null) {
            if (state.value.guildId != null) clearProtectedContent()
            return
        }
        if (state.value.guildId == guildId && boundPhotoId == photoId && boundCommentIds == ids) return
        if (state.value.guildId != null && (state.value.guildId != guildId || boundPhotoId != photoId)) {
            val nextDraftRevision = state.value.draftRevision + 1
            clearDraftsAndWrites()
            mutableState.value = GuildActionUiState(draftRevision = nextDraftRevision)
        }
        boundPhotoId = photoId
        boundCommentIds = ids
        mutableState.update { current ->
            current.copy(
                guildId = guildId,
                guildEligibility = current.guildEligibility?.takeIf { it.guildId == guildId },
                photoEligibility = current.photoEligibility?.takeIf { it.photoId == photoId },
                commentEligibility = current.commentEligibility.filterKeys(ids::contains),
                isLoadingEligibility = true,
                error = null,
            )
        }
        val requestGeneration = generation
        val requestRevision = ++eligibilityRevision
        eligibilityJob?.cancel()
        eligibilityJob = viewModelScope.launch {
            try {
                val scope = captureResourceScope(requestGeneration)
                if (!active(requestGeneration, scope)) return@launch
                val guild = actionService.guildEligibility(scope, guildId)
                val photo = photoId?.let { actionService.photoEligibility(scope, it) }
                val commentResults = ids.associateWith { actionService.commentEligibility(scope, it) }
                currentCoroutineContext().ensureActive()
                if (requestRevision != eligibilityRevision || !active(requestGeneration, scope)) return@launch
                mutableState.update {
                    it.copy(guildEligibility = guild, photoEligibility = photo,
                        commentEligibility = commentResults, isLoadingEligibility = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == generation && requestRevision == eligibilityRevision) handleError(error)
            } finally {
                if (requestGeneration == generation && requestRevision == eligibilityRevision) {
                    mutableState.update { it.copy(isLoadingEligibility = false) }
                }
            }
        }
    }

    fun openManager(currentDescription: String) {
        if (!canInteract || !state.value.canManageGuild) return
        mutableState.update { it.copy(isManagerOpen = true, managerDescription = currentDescription, error = null) }
        loadLabels()
    }

    fun closeManager() {
        if (state.value.isSavingGuildInfo) return
        uploadedAvatar = null
        mutableState.update { it.copy(isManagerOpen = false, avatarDraft = null, error = null, draftRevision = it.draftRevision + 1) }
    }

    fun updateManagerDescription(text: String) {
        if (!state.value.isSavingGuildInfo && text.length <= GuildInfoUpdate.MaximumDescriptionLength) {
            mutableState.update { it.copy(managerDescription = text, error = null) }
        }
    }

    fun setAvatarDraft(image: GuildImageUploadInput?) {
        if (!canUploadImages || state.value.isSavingGuildInfo) return
        uploadedAvatar = null
        mutableState.update { it.copy(avatarDraft = image?.safeCopy(), error = null, draftRevision = it.draftRevision + 1) }
    }

    fun saveAvatar() {
        val guildId = state.value.guildId ?: return
        val image = state.value.avatarDraft ?: return
        if (!requireManage() || !canUploadImages || state.value.isSavingGuildInfo) return
        mutableState.update { it.copy(isSavingGuildInfo = true, error = null) }
        launchWrite(finish = { it.copy(isSavingGuildInfo = false) }, uploadFailure = true) { scope, requestGeneration ->
            val uploaded = uploadedAvatar ?: requireNotNull(imageUploadService)
                .uploadImage(scope, GuildImagePurpose.Avatar, image)
                .also { if (active(requestGeneration, scope)) uploadedAvatar = it }
            if (!active(requestGeneration, scope)) return@launchWrite
            actionService.updateGuildInfo(scope, guildId, GuildInfoUpdate.Picture(uploaded))
            if (!active(requestGeneration, scope)) return@launchWrite
            uploadedAvatar = null
            mutableState.update { it.copy(avatarDraft = null, isManagerOpen = false,
                guildInfoSuccessRevision = it.guildInfoSuccessRevision + 1, draftRevision = it.draftRevision + 1) }
        }
    }

    fun saveGuildInfo(update: GuildInfoUpdate) {
        val guildId = state.value.guildId ?: return
        if (!requireManage() || state.value.isSavingGuildInfo || update is GuildInfoUpdate.Picture) return
        mutableState.update { it.copy(isSavingGuildInfo = true, error = null) }
        launchWrite(finish = { it.copy(isSavingGuildInfo = false) }) { scope, requestGeneration ->
            actionService.updateGuildInfo(scope, guildId, update)
            if (!active(requestGeneration, scope)) return@launchWrite
            mutableState.update { it.copy(isManagerOpen = false,
                guildInfoSuccessRevision = it.guildInfoSuccessRevision + 1) }
        }
    }

    fun setAlbumImages(images: List<GuildImageUploadInput>) {
        if (!prepareAlbumSelection(images.size)) return
        val owned = images.map(GuildImageUploadInput::safeCopy)
        resetAlbumProgress()
        albumSources = owned.map { input -> GuildAlbumImageSource { input.safeCopy() } }
        mutableState.update { it.copy(selectedAlbumImages = owned,
            selectedAlbumImageCount = owned.size, uploadedAlbumCount = 0,
            error = null, draftRevision = it.draftRevision + 1) }
    }

    fun setAlbumImageSources(sources: List<GuildAlbumImageSource>) {
        if (!prepareAlbumSelection(sources.size)) return
        resetAlbumProgress()
        albumSources = sources.toList()
        mutableState.update { it.copy(selectedAlbumImages = emptyList(),
            selectedAlbumImageCount = sources.size, uploadedAlbumCount = 0,
            error = null, draftRevision = it.draftRevision + 1) }
    }

    private fun prepareAlbumSelection(count: Int): Boolean {
        if (!canUploadImages || state.value.isUploadingAlbum || !state.value.canUploadAlbum) return false
        if (count <= 0 || count > GuildImagePurpose.Album.maximumSelectionCount) {
            mutableState.update { it.copy(error = GuildActionError.InvalidInput) }
            return false
        }
        return true
    }

    fun clearAlbumDraft() {
        if (state.value.isUploadingAlbum) return
        resetAlbumProgress()
        albumSources = emptyList()
        mutableState.update { it.copy(selectedAlbumImages = emptyList(), selectedAlbumImageCount = 0,
            uploadedAlbumCount = 0,
            error = null, draftRevision = it.draftRevision + 1) }
    }

    fun uploadAlbum() {
        val guildId = state.value.guildId ?: return
        val sources = albumSources
        if (!requireUploadAlbum() || !canUploadImages || state.value.isUploadingAlbum || sources.isEmpty()) return
        mutableState.update {
            it.copy(isUploadingAlbum = true, uploadedAlbumCount = uploadedAlbumImages.size, error = null)
        }
        val requestGeneration = generation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val scope = resourceScope ?: throw GuildException.Unavailable
                if (!active(requestGeneration, scope)) return@launch
                mutableState.update { it.copy(uploadedAlbumCount = uploadedAlbumImages.size) }
                while (uploadedAlbumImages.size < sources.size) {
                    val image = sources[uploadedAlbumImages.size].read()
                    if (!active(requestGeneration, scope)) return@launch
                    val uploaded = requireNotNull(imageUploadService)
                        .uploadImage(scope, GuildImagePurpose.Album, image)
                    if (!active(requestGeneration, scope)) return@launch
                    uploadedAlbumImages += uploaded
                    mutableState.update { it.copy(uploadedAlbumCount = uploadedAlbumImages.size) }
                }
                actionService.registerAlbumPhotos(scope, guildId, uploadedAlbumImages.toList())
                if (!active(requestGeneration, scope)) return@launch
                albumSources = emptyList()
                uploadedAlbumImages.clear()
                mutableState.update { it.copy(selectedAlbumImages = emptyList(), selectedAlbumImageCount = 0,
                    uploadedAlbumCount = 0, albumSuccessRevision = it.albumSuccessRevision + 1,
                    draftRevision = it.draftRevision + 1) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == generation) handleError(error, uploadFailure = true)
            } finally {
                if (requestGeneration == generation) mutableState.update { it.copy(isUploadingAlbum = false) }
            }
        }
        writeJobs += job
        job.invokeOnCompletion { writeJobs -= job }
        job.start()
    }

    fun togglePhotoLike(photoId: Int) {
        if (photoId != boundPhotoId || !requireInteraction() || state.value.isLikingPhoto) return
        mutableState.update { it.copy(isLikingPhoto = true, error = null) }
        launchWrite(finish = { it.copy(isLikingPhoto = false) }) { scope, requestGeneration ->
            val result = actionService.togglePhotoLike(scope, photoId)
            if (!active(requestGeneration, scope)) return@launchWrite
            mutableState.update { it.copy(likeResults = it.likeResults + (photoId to result)) }
        }
    }

    fun openComment(target: GuildCommentTarget = GuildCommentTarget.TopLevel) {
        if (!requireInteraction() || state.value.isSubmittingComment) return
        mutableState.update { it.copy(commentTarget = target, error = null) }
    }

    fun updateCommentText(text: String) {
        if (!state.value.isSubmittingComment) mutableState.update { it.copy(commentText = text, error = null) }
    }

    fun setCommentImage(image: GuildImageUploadInput?) {
        if (!canUploadImages || state.value.isSubmittingComment) return
        uploadedCommentImage = null
        mutableState.update { it.copy(commentImage = image?.safeCopy(), error = null, draftRevision = it.draftRevision + 1) }
    }

    fun closeComment(keepDraft: Boolean = true) {
        if (state.value.isSubmittingComment) return
        if (!keepDraft) uploadedCommentImage = null
        mutableState.update {
            if (keepDraft) it.copy(commentTarget = null, error = null)
            else it.copy(commentTarget = null, commentText = "", commentImage = null, error = null,
                draftRevision = it.draftRevision + 1)
        }
    }

    fun submitComment(photoId: Int) {
        if (photoId != boundPhotoId) return
        val snapshot = state.value
        val target = snapshot.commentTarget ?: return
        val text = snapshot.commentText.trim()
        val localImage = snapshot.commentImage
        if (!requireInteraction() || snapshot.isSubmittingComment || text.isEmpty() && localImage == null) {
            if (text.isEmpty() && localImage == null) mutableState.update { it.copy(error = GuildActionError.InvalidInput) }
            return
        }
        if (localImage != null && !canUploadImages) return
        mutableState.update { it.copy(isSubmittingComment = true, error = null) }
        launchWrite(finish = { it.copy(isSubmittingComment = false) }, uploadFailure = localImage != null) { scope, requestGeneration ->
            val uploaded = localImage?.let {
                uploadedCommentImage ?: requireNotNull(imageUploadService)
                    .uploadImage(scope, GuildImagePurpose.Comment, it)
                    .also { image -> if (active(requestGeneration, scope)) uploadedCommentImage = image }
            }
            if (!active(requestGeneration, scope)) return@launchWrite
            val mentions = if (target.authorUuid.isNullOrBlank() || target.authorName.isNullOrBlank()) emptyList()
                else listOf(GuildPhotoCommentMention(target.authorUuid, target.authorName))
            actionService.commentPhoto(scope, GuildPhotoCommentDraft(
                photoId = photoId,
                contentHtml = text.guildCommentHtml(),
                mentions = mentions,
                parentId = target.parentId,
                rootParentId = target.rootParentId,
                commentImage = uploaded,
            ))
            if (!active(requestGeneration, scope)) return@launchWrite
            uploadedCommentImage = null
            mutableState.update { it.copy(commentTarget = null, commentText = "", commentImage = null,
                commentSuccessRevision = it.commentSuccessRevision + 1, draftRevision = it.draftRevision + 1) }
        }
    }

    fun requestDeleteComment(id: Int) {
        if (state.value.canDeleteComment(id) && id !in state.value.deletingCommentIds) {
            mutableState.update { it.copy(confirmDeleteCommentId = id, error = null) }
        }
    }

    fun cancelDeleteComment() { mutableState.update { it.copy(confirmDeleteCommentId = null) } }

    fun confirmDeleteComment() {
        val id = state.value.confirmDeleteCommentId ?: return
        if (!state.value.canDeleteComment(id) || id in state.value.deletingCommentIds || !requireInteraction()) return
        mutableState.update { it.copy(confirmDeleteCommentId = null,
            deletingCommentIds = it.deletingCommentIds + id, error = null) }
        launchWrite(finish = { it.copy(deletingCommentIds = it.deletingCommentIds - id) }) { scope, requestGeneration ->
            actionService.deleteOwnComment(scope, id)
            if (!active(requestGeneration, scope)) return@launchWrite
            mutableState.update { it.copy(deletedCommentId = id,
                deletedCommentRevision = it.deletedCommentRevision + 1) }
        }
    }

    fun requestDeletePhoto(id: Int) {
        if (state.value.canDeletePhoto(id) && !state.value.isDeletingPhoto) {
            mutableState.update { it.copy(confirmDeletePhotoId = id, error = null) }
        }
    }

    fun cancelDeletePhoto() { mutableState.update { it.copy(confirmDeletePhotoId = null) } }

    fun confirmDeletePhoto() {
        val id = state.value.confirmDeletePhotoId ?: return
        if (!state.value.canDeletePhoto(id) || state.value.isDeletingPhoto || !requireInteraction()) return
        mutableState.update { it.copy(confirmDeletePhotoId = null, isDeletingPhoto = true, error = null) }
        launchWrite(finish = { it.copy(isDeletingPhoto = false) }) { scope, requestGeneration ->
            actionService.deletePhoto(scope, id)
            if (!active(requestGeneration, scope)) return@launchWrite
            mutableState.update { it.copy(deletedPhotoId = id,
                deletedPhotoRevision = it.deletedPhotoRevision + 1) }
        }
    }

    fun consumeDeletedCommentEvent(id: Int) {
        mutableState.update { current ->
            if (current.deletedCommentId == id) current.copy(deletedCommentId = null) else current
        }
    }

    fun consumeDeletedPhotoEvent(id: Int) {
        mutableState.update { current ->
            if (current.deletedPhotoId == id) current.copy(deletedPhotoId = null) else current
        }
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun clearProtectedContent() {
        if (cleared) return
        clearDraftsAndWrites()
        mutableState.value = GuildActionUiState(draftRevision = state.value.draftRevision + 1)
    }

    override fun onCleared() {
        clearProtectedContent()
        cleared = true
        super.onCleared()
    }

    private fun loadLabels() {
        if (state.value.labels.isNotEmpty() || state.value.isLoadingLabels || !requireManage()) return
        val requestGeneration = generation
        mutableState.update { it.copy(isLoadingLabels = true, error = null) }
        viewModelScope.launch {
            try {
                val scope = resourceScope ?: throw GuildException.Unavailable
                if (!active(requestGeneration, scope)) return@launch
                val labels = actionService.labels(scope)
                if (!active(requestGeneration, scope)) return@launch
                mutableState.update { it.copy(labels = labels, isLoadingLabels = false) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (requestGeneration == generation) handleError(error)
            } finally {
                if (requestGeneration == generation) mutableState.update { it.copy(isLoadingLabels = false) }
            }
        }
    }

    private fun requireInteraction(): Boolean {
        synchronizeEligibility()
        if (canInteract) return true
        mutableState.update { it.copy(error = GuildActionError.Unavailable) }
        return false
    }

    private fun requireManage(): Boolean = requireInteraction() && state.value.canManageGuild
    private fun requireUploadAlbum(): Boolean = requireInteraction() && state.value.canUploadAlbum

    // A draft belongs to the credential under which its resource was opened.
    // Never capture a replacement credential when submitting an existing draft.
    private suspend fun captureResourceScope(requestGeneration: Long): GuildActionScope {
        resourceScope?.let { return it }
        val opened = actionService.beginActionScope()
        try {
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation || cleared) throw CancellationException()
            resourceScope = opened
            return opened
        } finally {
            if (resourceScope !== opened) opened.close()
        }
    }

    private suspend fun active(requestGeneration: Long, scope: GuildActionScope): Boolean {
        currentCoroutineContext().ensureActive()
        if (requestGeneration != generation || cleared) return false
        if (!scope.isCurrent() || !canInteract) throw GuildException.AuthenticationRequired
        return true
    }

    private fun launchWrite(
        finish: (GuildActionUiState) -> GuildActionUiState,
        uploadFailure: Boolean = false,
        block: suspend (GuildActionScope, Long) -> Unit,
    ) {
        val requestGeneration = generation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val scope = resourceScope ?: throw GuildException.Unavailable
                if (active(requestGeneration, scope)) block(scope, requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration == generation) handleError(error, uploadFailure)
            } finally {
                if (requestGeneration == generation) mutableState.update(finish)
            }
        }
        writeJobs += job
        job.invokeOnCompletion { writeJobs -= job }
        job.start()
    }

    private fun handleError(error: Exception, uploadFailure: Boolean = false) {
        val mapped = when (error) {
            GuildException.AuthenticationRequired, GuildException.IdentityConflict ->
                GuildActionError.AuthenticationRequired
            GuildException.Unavailable -> GuildActionError.Unavailable
            GuildException.ActionNotEligible -> GuildActionError.Unavailable
            GuildException.ImageUploadFailed -> GuildActionError.UploadFailed
            is IllegalArgumentException, GuildException.InvalidResponse -> GuildActionError.InvalidInput
            else -> if (uploadFailure) GuildActionError.UploadFailed else GuildActionError.Failed
        }
        if (error == GuildException.AuthenticationRequired || error == GuildException.IdentityConflict ||
            error == GuildException.Unavailable
        ) {
            clearProtectedContent()
        }
        mutableState.update { it.copy(error = mapped) }
    }

    private fun clearDraftsAndWrites() {
        ++generation
        ++eligibilityRevision
        eligibilityJob?.cancel()
        eligibilityJob = null
        writeJobs.toList().forEach(Job::cancel)
        writeJobs.clear()
        boundPhotoId = null
        boundCommentIds = emptySet()
        resourceScope?.close()
        resourceScope = null
        uploadedAvatar = null
        uploadedCommentImage = null
        resetAlbumProgress()
        albumSources = emptyList()
    }

    private fun resetAlbumProgress() {
        uploadedAlbumImages.clear()
    }
}

class GuildActionViewModelFactory(
    private val actionService: GuildActionService,
    private val imageUploadService: GuildImageUploadService? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        GuildActionViewModel(actionService, imageUploadService) as T
}

private fun GuildImageUploadInput.safeCopy(): GuildImageUploadInput = GuildImageUploadInput(copyBytes(), mimeType)

private fun String.guildCommentHtml(): String = buildString(length) {
    for (character in this@guildCommentHtml) when (character) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        '\n' -> append("<br>")
        else -> append(character)
    }
}
