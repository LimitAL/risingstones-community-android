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
import top.cxmeow.risingstones.feature.guild.domain.GuildActivitySummary
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildInfo
import top.cxmeow.risingstones.feature.guild.domain.GuildMember
import top.cxmeow.risingstones.feature.guild.domain.GuildMembers
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoDetail
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoSummary
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild

enum class GuildSection { Profile, Members, Activities, Photos }

enum class GuildUiError { AuthenticationRequired, Unavailable, Failed }

data class GuildUiState(
    val section: GuildSection = GuildSection.Profile,
    val ownGuild: OwnGuild? = null,
    val info: GuildInfo? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: GuildUiError? = null,
    val members: GuildMembers? = null,
    val isLoadingMembers: Boolean = false,
    val membersError: GuildUiError? = null,
    val activities: List<GuildActivitySummary> = emptyList(),
    val activityPage: Int = 0,
    val hasMoreActivities: Boolean = false,
    val isLoadingActivities: Boolean = false,
    val activitiesError: GuildUiError? = null,
    val photos: List<GuildPhotoSummary> = emptyList(),
    val photoPage: Int = 0,
    val hasMorePhotos: Boolean = false,
    val isLoadingPhotos: Boolean = false,
    val photosError: GuildUiError? = null,
    val selectedPhotoId: Int? = null,
    val photoDetail: GuildPhotoDetail? = null,
    val isLoadingPhotoDetail: Boolean = false,
    val photoDetailError: GuildUiError? = null,
    val comments: List<GuildPhotoComment> = emptyList(),
    val commentPage: Int = 0,
    val nextCommentPageTime: String? = null,
    val hasMoreComments: Boolean = false,
    val isLoadingComments: Boolean = false,
    val commentsError: GuildUiError? = null,
    val selectedCommentRootId: Int? = null,
    val replies: List<GuildPhotoComment> = emptyList(),
    val replyPage: Int = 0,
    val hasMoreReplies: Boolean = false,
    val isLoadingReplies: Boolean = false,
    val repliesError: GuildUiError? = null,
) {
    val hasNoGuild: Boolean get() = ownGuild == OwnGuild.None
}

class GuildViewModel(
    private val service: GuildService,
    autoLoad: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GuildUiState())
    val state: StateFlow<GuildUiState> = mutableState.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private var nextRevision = 0L
    private var cleared = false

    init {
        if (autoLoad) loadGuild(refresh = false)
    }

    fun retry() = loadGuild(refresh = state.value.info != null || state.value.hasNoGuild)

    fun refresh() = loadGuild(refresh = true)

    fun selectSection(section: GuildSection) {
        if (cleared || mutableState.value.section == section) return
        when (mutableState.value.section) {
            GuildSection.Profile -> Unit
            GuildSection.Members -> cancel("members", resetLoading = true)
            GuildSection.Activities -> cancel("activities", resetLoading = true)
            GuildSection.Photos -> {
                cancel("photos", resetLoading = true)
                cancel("photo", resetLoading = true)
                cancel("comments", resetLoading = true)
                cancel("replies", resetLoading = true)
            }
        }
        mutableState.update { it.copy(section = section) }
        loadSectionIfNeeded(section)
    }

    fun refreshSection() {
        when (state.value.section) {
            GuildSection.Profile -> refresh()
            GuildSection.Members -> loadMembers(force = true)
            GuildSection.Activities -> loadActivities(refresh = true)
            GuildSection.Photos -> loadPhotos(refresh = true)
        }
    }

    fun loadMoreActivities() {
        if (!state.value.hasMoreActivities || state.value.isLoadingActivities) return
        loadActivities(refresh = false)
    }

    fun loadMorePhotos() {
        if (!state.value.hasMorePhotos || state.value.isLoadingPhotos) return
        loadPhotos(refresh = false)
    }

    fun refreshPhotos() = loadPhotos(refresh = true)

    fun selectPhoto(id: Int) {
        if (cleared || id <= 0 || state.value.photos.none { it.id == id }) return
        if (state.value.selectedPhotoId == id && state.value.photoDetail?.id == id) return
        cancel("photo", resetLoading = true)
        cancel("comments", resetLoading = true)
        dismissReplies()
        mutableState.update {
            it.copy(
                selectedPhotoId = id,
                photoDetail = null,
                isLoadingPhotoDetail = true,
                photoDetailError = null,
                comments = emptyList(),
                commentPage = 0,
                nextCommentPageTime = null,
                hasMoreComments = false,
                commentsError = null,
            )
        }
        launch("photo", onFinish = { mutableState.update { it.copy(isLoadingPhotoDetail = false) } }) { request ->
            val detail = service.photo(id)
            val expectedGuild = currentGuildId() ?: return@launch
            if (!request.accept()) return@launch
            if (detail.id != id || detail.guildId != expectedGuild) throw GuildException.InvalidResponse
            mutableState.update { it.copy(photoDetail = detail) }
            loadComments(refresh = true)
        }
    }

    fun retryPhoto() {
        if (state.value.photoDetail == null) {
            state.value.selectedPhotoId?.let(::selectPhotoAfterFailure)
        } else {
            refreshPhoto()
        }
    }

    fun refreshPhoto() {
        if (cleared) return
        val id = state.value.selectedPhotoId ?: return
        cancel("photo", resetLoading = true)
        cancel("comments", resetLoading = true)
        mutableState.update { it.copy(isLoadingPhotoDetail = true, photoDetailError = null) }
        launch("photo", onFinish = { mutableState.update { it.copy(isLoadingPhotoDetail = false) } }) { request ->
            val detail = service.photo(id)
            val expectedGuild = currentGuildId() ?: return@launch
            if (!request.accept()) return@launch
            if (detail.id != id || detail.guildId != expectedGuild) throw GuildException.InvalidResponse
            mutableState.update { it.copy(photoDetail = detail, photoDetailError = null) }
            loadComments(refresh = true)
        }
    }

    fun clearPhotoSelection() {
        cancel("photo", resetLoading = true)
        cancel("comments", resetLoading = true)
        dismissReplies()
        mutableState.update {
            it.copy(
                selectedPhotoId = null,
                photoDetail = null,
                isLoadingPhotoDetail = false,
                photoDetailError = null,
                comments = emptyList(),
                commentPage = 0,
                nextCommentPageTime = null,
                hasMoreComments = false,
                isLoadingComments = false,
                commentsError = null,
            )
        }
    }

    fun refreshComments() = loadComments(refresh = true)

    fun loadMoreComments() {
        if (!state.value.hasMoreComments || state.value.isLoadingComments) return
        loadComments(refresh = false)
    }

    fun openReplies(comment: GuildPhotoComment) {
        if (cleared || comment.childCount <= 0 || state.value.comments.none { it.id == comment.id }) return
        cancel("replies", resetLoading = true)
        mutableState.update {
            it.copy(
                selectedCommentRootId = comment.rootParentId.takeIf { root -> root > 0 } ?: comment.id,
                replies = emptyList(),
                replyPage = 0,
                hasMoreReplies = false,
                repliesError = null,
            )
        }
        loadReplies(refresh = true)
    }

    fun loadMoreReplies() {
        if (!state.value.hasMoreReplies || state.value.isLoadingReplies) return
        loadReplies(refresh = false)
    }

    fun retryReplies() = loadReplies(refresh = state.value.replyPage == 0)

    fun refreshReplies() = loadReplies(refresh = true)

    fun applyDeletedComment(id: Int) {
        if (id <= 0) return
        val deletedTopLevel = state.value.comments.any { it.id == id }
        mutableState.update { current ->
            current.copy(
                comments = current.comments.filterNot { it.id == id }.map { comment ->
                    if (!deletedTopLevel && comment.id == current.selectedCommentRootId) {
                        comment.copy(childCount = (comment.childCount - 1).coerceAtLeast(0))
                    } else comment
                },
                replies = current.replies.filterNot { it.id == id },
                photoDetail = current.photoDetail?.let {
                    if (deletedTopLevel) it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) else it
                },
                selectedCommentRootId = current.selectedCommentRootId.takeUnless { deletedTopLevel && it == id },
            )
        }
    }

    fun applyDeletedPhoto(id: Int) {
        if (id <= 0) return
        mutableState.update { it.copy(photos = it.photos.filterNot { photo -> photo.id == id }) }
        if (state.value.selectedPhotoId == id) clearPhotoSelection()
    }

    fun dismissReplies() {
        cancel("replies", resetLoading = true)
        mutableState.update {
            it.copy(
                selectedCommentRootId = null,
                replies = emptyList(),
                replyPage = 0,
                hasMoreReplies = false,
                isLoadingReplies = false,
                repliesError = null,
            )
        }
    }

    fun clearProtectedContent() {
        clearProtectedContent(GuildUiError.AuthenticationRequired)
    }

    private fun clearProtectedContent(error: GuildUiError) {
        if (cleared) return
        revisions.keys.toList().forEach(::cancel)
        mutableState.value = GuildUiState(error = error)
    }

    override fun onCleared() {
        clearProtectedContent()
        cleared = true
        super.onCleared()
    }

    private fun loadGuild(refresh: Boolean) {
        if (cleared) return
        if (!service.canRead) {
            clearProtectedContent()
            return
        }
        val hadContent = state.value.info != null || state.value.hasNoGuild
        mutableState.update {
            it.copy(
                isLoading = !hadContent,
                isRefreshing = refresh && hadContent,
                error = null,
            )
        }
        launch("guild", onFinish = {
            mutableState.update { it.copy(isLoading = false, isRefreshing = false) }
        }) { request ->
            val own = service.ownGuild()
            val info = when (own) {
                OwnGuild.None -> null
                is OwnGuild.Joined -> service.info(own.guildId)
            }
            if (!request.accept()) return@launch
            val oldGuild = currentGuildId()
            val newGuild = (own as? OwnGuild.Joined)?.guildId
            if (oldGuild != newGuild) cancelGuildChildren()
            mutableState.update { current ->
                if (oldGuild != newGuild) {
                    GuildUiState(section = current.section, ownGuild = own, info = info)
                } else {
                    current.copy(ownGuild = own, info = info, error = null)
                }
            }
            if (refresh && newGuild != null) loadSectionIfNeeded(state.value.section, force = true)
        }
    }

    private fun loadSectionIfNeeded(section: GuildSection, force: Boolean = false) {
        if (currentGuildId() == null) return
        when (section) {
            GuildSection.Profile -> Unit
            GuildSection.Members -> if (force || state.value.members == null) loadMembers(force)
            GuildSection.Activities -> if (force || state.value.activityPage == 0) loadActivities(refresh = true)
            GuildSection.Photos -> {
                if (force || state.value.photoPage == 0) loadPhotos(refresh = true)
                val selected = state.value.selectedPhotoId
                if (selected != null && state.value.photoDetail == null && !state.value.isLoadingPhotoDetail) {
                    selectPhotoAfterFailure(selected)
                } else if (selected != null && state.value.photoDetail != null) {
                    if (state.value.commentPage == 0 && state.value.comments.isEmpty() &&
                        state.value.commentsError == null && !state.value.isLoadingComments
                    ) {
                        loadComments(refresh = true)
                    }
                    if (state.value.selectedCommentRootId != null && state.value.replyPage == 0 &&
                        state.value.replies.isEmpty() && state.value.repliesError == null &&
                        !state.value.isLoadingReplies
                    ) {
                        loadReplies(refresh = true)
                    }
                }
            }
        }
    }

    private fun loadMembers(force: Boolean) {
        if (state.value.isLoadingMembers || (!force && state.value.members != null)) return
        val guildId = currentGuildId() ?: return
        mutableState.update { it.copy(isLoadingMembers = true, membersError = null) }
        launch("members", onFinish = { mutableState.update { it.copy(isLoadingMembers = false) } }) { request ->
            val result = service.members(guildId)
            if (request.accept() && currentGuildId() == guildId) {
                mutableState.update { it.copy(members = result, membersError = null) }
            }
        }
    }

    private fun loadActivities(refresh: Boolean) {
        if (state.value.isLoadingActivities) return
        val guildId = currentGuildId() ?: return
        val page = if (refresh) 1 else state.value.activityPage + 1
        mutableState.update { it.copy(isLoadingActivities = true, activitiesError = null) }
        launch("activities", onFinish = { mutableState.update { it.copy(isLoadingActivities = false) } }) { request ->
            val result = service.activities(guildId, page)
            if (!request.accept() || currentGuildId() != guildId) return@launch
            mutableState.update { current ->
                current.copy(
                    activities = mergePage(if (refresh) emptyList() else current.activities, result.items) { it.id },
                    activityPage = result.page,
                    hasMoreActivities = result.hasMore && result.items.isNotEmpty(),
                    activitiesError = null,
                )
            }
        }
    }

    private fun loadPhotos(refresh: Boolean) {
        if (state.value.isLoadingPhotos) return
        val guildId = currentGuildId() ?: return
        val page = if (refresh) 1 else state.value.photoPage + 1
        mutableState.update { it.copy(isLoadingPhotos = true, photosError = null) }
        launch("photos", onFinish = { mutableState.update { it.copy(isLoadingPhotos = false) } }) { request ->
            val result = service.photos(guildId, page)
            if (!request.accept() || currentGuildId() != guildId) return@launch
            mutableState.update { current ->
                current.copy(
                    photos = mergePage(if (refresh) emptyList() else current.photos, result.items) { it.id },
                    photoPage = result.page,
                    hasMorePhotos = result.hasMore && result.items.isNotEmpty(),
                    photosError = null,
                )
            }
        }
    }

    private fun selectPhotoAfterFailure(id: Int) {
        mutableState.update { it.copy(selectedPhotoId = null) }
        selectPhoto(id)
    }

    private fun loadComments(refresh: Boolean) {
        if (state.value.isLoadingComments) return
        val photoId = state.value.selectedPhotoId ?: return
        val page = if (refresh) 1 else state.value.commentPage + 1
        val pageTime = if (refresh) null else state.value.nextCommentPageTime
        mutableState.update { it.copy(isLoadingComments = true, commentsError = null) }
        launch("comments", onFinish = { mutableState.update { it.copy(isLoadingComments = false) } }) { request ->
            val result = service.comments(photoId, page, pageTime)
            if (!request.accept() || state.value.selectedPhotoId != photoId) return@launch
            mutableState.update { current ->
                current.copy(
                    comments = mergePage(if (refresh) emptyList() else current.comments, result.items) { it.id },
                    commentPage = result.page,
                    nextCommentPageTime = result.nextPageTime,
                    hasMoreComments = result.hasMore && result.items.isNotEmpty(),
                    commentsError = null,
                )
            }
        }
    }

    private fun loadReplies(refresh: Boolean) {
        if (state.value.isLoadingReplies) return
        val rootId = state.value.selectedCommentRootId ?: return
        val page = if (refresh) 1 else state.value.replyPage + 1
        mutableState.update { it.copy(isLoadingReplies = true, repliesError = null) }
        launch("replies", onFinish = { mutableState.update { it.copy(isLoadingReplies = false) } }) { request ->
            val result = service.replies(rootId, page)
            if (!request.accept() || state.value.selectedCommentRootId != rootId) return@launch
            mutableState.update { current ->
                current.copy(
                    replies = mergePage(if (refresh) emptyList() else current.replies, result.items) { it.id },
                    replyPage = result.page,
                    hasMoreReplies = result.hasMore && result.items.isNotEmpty(),
                    repliesError = null,
                )
            }
        }
    }

    private fun currentGuildId(): GuildId? = (state.value.ownGuild as? OwnGuild.Joined)?.guildId

    private fun cancelGuildChildren() {
        listOf("members", "activities", "photos", "photo", "comments", "replies").forEach(::cancel)
    }

    private inner class Request(private val key: String, private val revision: Long) {
        suspend fun accept(): Boolean {
            currentCoroutineContext().ensureActive()
            if (cleared || revisions[key] != revision) return false
            if (!service.canRead) {
                clearProtectedContent(GuildUiError.AuthenticationRequired)
                return false
            }
            return true
        }
    }

    private fun launch(
        key: String,
        onFinish: () -> Unit,
        block: suspend (Request) -> Unit,
    ) {
        cancel(key)
        val revision = ++nextRevision
        revisions[key] = revision
        val request = Request(key, revision)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (revisions[key] != revision || cleared) return@launch
                val mapped = error.guildUiError()
                if (mapped == GuildUiError.AuthenticationRequired || mapped == GuildUiError.Unavailable) {
                    clearProtectedContent(mapped)
                } else {
                    setError(key, mapped)
                }
            } finally {
                if (revisions[key] == revision) {
                    revisions.remove(key)
                    jobs.remove(key)
                    onFinish()
                }
            }
        }
        jobs[key] = job
        job.start()
    }

    private fun setError(key: String, error: GuildUiError) {
        mutableState.update {
            when (key) {
                "guild" -> it.copy(error = error)
                "members" -> it.copy(membersError = error)
                "activities" -> it.copy(activitiesError = error)
                "photos" -> it.copy(photosError = error)
                "photo" -> it.copy(photoDetailError = error)
                "comments" -> it.copy(commentsError = error)
                "replies" -> it.copy(repliesError = error)
                else -> it
            }
        }
    }

    private fun cancel(key: String, resetLoading: Boolean = false) {
        revisions.remove(key)
        jobs.remove(key)?.cancel()
        if (resetLoading) {
            mutableState.update {
                when (key) {
                    "members" -> it.copy(isLoadingMembers = false)
                    "activities" -> it.copy(isLoadingActivities = false)
                    "photos" -> it.copy(isLoadingPhotos = false)
                    "photo" -> it.copy(isLoadingPhotoDetail = false)
                    "comments" -> it.copy(isLoadingComments = false)
                    "replies" -> it.copy(isLoadingReplies = false)
                    else -> it
                }
            }
        }
    }
}

data class GuildPhotoUiState(
    val photoId: Int,
    val detail: GuildPhotoDetail? = null,
    val isLoading: Boolean = false,
    val error: GuildUiError? = null,
    val comments: List<GuildPhotoComment> = emptyList(),
    val commentPage: Int = 0,
    val nextCommentPageTime: String? = null,
    val hasMoreComments: Boolean = false,
    val isLoadingComments: Boolean = false,
    val commentsError: GuildUiError? = null,
    val selectedCommentRootId: Int? = null,
    val replies: List<GuildPhotoComment> = emptyList(),
    val replyPage: Int = 0,
    val hasMoreReplies: Boolean = false,
    val isLoadingReplies: Boolean = false,
    val repliesError: GuildUiError? = null,
)

class GuildPhotoViewModel(
    private val service: GuildService,
    photoId: Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GuildPhotoUiState(photoId))
    val state: StateFlow<GuildPhotoUiState> = mutableState.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private var nextRevision = 0L
    private var cleared = false

    init {
        require(photoId > 0)
        loadPhoto()
    }

    fun retryPhoto() = loadPhoto()
    fun refreshComments() = loadComments(refresh = true)
    fun loadMoreComments() {
        if (state.value.hasMoreComments && !state.value.isLoadingComments) loadComments(refresh = false)
    }

    fun openReplies(comment: GuildPhotoComment) {
        if (comment.childCount <= 0 || state.value.comments.none { it.id == comment.id }) return
        cancel("replies", resetLoading = true)
        mutableState.update {
            it.copy(selectedCommentRootId = comment.rootParentId.takeIf { root -> root > 0 } ?: comment.id,
                replies = emptyList(), replyPage = 0, hasMoreReplies = false, repliesError = null)
        }
        loadReplies(refresh = true)
    }

    fun loadMoreReplies() {
        if (state.value.hasMoreReplies && !state.value.isLoadingReplies) loadReplies(refresh = false)
    }
    fun retryReplies() = loadReplies(refresh = state.value.replyPage == 0)
    fun refreshReplies() = loadReplies(refresh = true)
    fun applyDeletedComment(id: Int) {
        if (id <= 0) return
        val deletedTopLevel = state.value.comments.any { it.id == id }
        mutableState.update { current -> current.copy(
            comments = current.comments.filterNot { it.id == id }.map { comment ->
                if (!deletedTopLevel && comment.id == current.selectedCommentRootId) {
                    comment.copy(childCount = (comment.childCount - 1).coerceAtLeast(0))
                } else comment
            },
            replies = current.replies.filterNot { it.id == id },
            detail = current.detail?.let {
                if (deletedTopLevel) it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) else it
            },
            selectedCommentRootId = current.selectedCommentRootId.takeUnless { deletedTopLevel && it == id },
        ) }
    }
    fun dismissReplies() {
        cancel("replies", resetLoading = true)
        mutableState.update { it.copy(selectedCommentRootId = null, replies = emptyList(), replyPage = 0,
            hasMoreReplies = false, isLoadingReplies = false, repliesError = null) }
    }

    fun clearProtectedContent() {
        clearProtectedContent(GuildUiError.AuthenticationRequired)
    }

    override fun onCleared() {
        clearProtectedContent()
        cleared = true
        super.onCleared()
    }

    private fun loadPhoto() {
        if (cleared) return
        if (!service.canRead) {
            clearProtectedContent()
            return
        }
        val id = state.value.photoId
        mutableState.update { it.copy(isLoading = true, error = null) }
        launch("photo", { mutableState.update { it.copy(isLoading = false) } }) { request ->
            val detail = service.photo(id)
            if (!request.accept()) return@launch
            if (detail.id != id) throw GuildException.InvalidResponse
            mutableState.update { it.copy(detail = detail, error = null) }
            loadComments(refresh = true)
        }
    }

    private fun loadComments(refresh: Boolean) {
        if (state.value.isLoadingComments) return
        val id = state.value.photoId
        val page = if (refresh) 1 else state.value.commentPage + 1
        val pageTime = if (refresh) null else state.value.nextCommentPageTime
        mutableState.update { it.copy(isLoadingComments = true, commentsError = null) }
        launch("comments", { mutableState.update { it.copy(isLoadingComments = false) } }) { request ->
            val result = service.comments(id, page, pageTime)
            if (!request.accept()) return@launch
            mutableState.update { current -> current.copy(
                comments = mergePage(if (refresh) emptyList() else current.comments, result.items) { it.id },
                commentPage = result.page, nextCommentPageTime = result.nextPageTime,
                hasMoreComments = result.hasMore && result.items.isNotEmpty(), commentsError = null) }
        }
    }

    private fun loadReplies(refresh: Boolean) {
        if (state.value.isLoadingReplies) return
        val rootId = state.value.selectedCommentRootId ?: return
        val page = if (refresh) 1 else state.value.replyPage + 1
        mutableState.update { it.copy(isLoadingReplies = true, repliesError = null) }
        launch("replies", { mutableState.update { it.copy(isLoadingReplies = false) } }) { request ->
            val result = service.replies(rootId, page)
            if (!request.accept() || state.value.selectedCommentRootId != rootId) return@launch
            mutableState.update { current -> current.copy(
                replies = mergePage(if (refresh) emptyList() else current.replies, result.items) { it.id },
                replyPage = result.page, hasMoreReplies = result.hasMore && result.items.isNotEmpty(),
                repliesError = null) }
        }
    }

    private inner class Request(private val key: String, private val revision: Long) {
        suspend fun accept(): Boolean {
            currentCoroutineContext().ensureActive()
            if (cleared || revisions[key] != revision) return false
            if (!service.canRead) {
                clearProtectedContent()
                return false
            }
            return true
        }
    }

    private fun launch(key: String, onFinish: () -> Unit, block: suspend (Request) -> Unit) {
        cancel(key)
        val revision = ++nextRevision
        revisions[key] = revision
        val request = Request(key, revision)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (revisions[key] != revision || cleared) return@launch
                val mapped = error.guildUiError()
                if (mapped == GuildUiError.AuthenticationRequired || mapped == GuildUiError.Unavailable) {
                    clearProtectedContent(mapped)
                }
                else mutableState.update {
                    when (key) {
                        "photo" -> it.copy(error = mapped)
                        "comments" -> it.copy(commentsError = mapped)
                        "replies" -> it.copy(repliesError = mapped)
                        else -> it
                    }
                }
            } finally {
                if (revisions[key] == revision) {
                    revisions.remove(key)
                    jobs.remove(key)
                    onFinish()
                }
            }
        }
        jobs[key] = job
        job.start()
    }

    private fun clearProtectedContent(error: GuildUiError) {
        if (cleared) return
        revisions.keys.toList().forEach(::cancel)
        mutableState.value = GuildPhotoUiState(state.value.photoId, error = error)
    }

    private fun cancel(key: String, resetLoading: Boolean = false) {
        revisions.remove(key)
        jobs.remove(key)?.cancel()
        if (resetLoading) {
            mutableState.update {
                when (key) {
                    "photo" -> it.copy(isLoading = false)
                    "comments" -> it.copy(isLoadingComments = false)
                    "replies" -> it.copy(isLoadingReplies = false)
                    else -> it
                }
            }
        }
    }
}

class GuildViewModelFactory(
    private val service: GuildService,
    private val autoLoad: Boolean = true,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GuildViewModel(service, autoLoad) as T
}

class GuildPhotoViewModelFactory(
    private val service: GuildService,
    private val photoId: Int,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GuildPhotoViewModel(service, photoId) as T
}

private fun Throwable.guildUiError(): GuildUiError = when (this) {
    GuildException.AuthenticationRequired -> GuildUiError.AuthenticationRequired
    GuildException.Unavailable -> GuildUiError.Unavailable
    else -> GuildUiError.Failed
}

private inline fun <T, K> mergePage(existing: List<T>, incoming: List<T>, key: (T) -> K): List<T> =
    (existing + incoming).distinctBy(key)
