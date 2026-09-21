package top.cxmeow.risingstones.feature.forum.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumComment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumActionEligibilityService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentAuthoringService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentEmojiNumbers
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentMention
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumMentionCandidate
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumImageUploadService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumInteractionService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostInteraction
import top.cxmeow.risingstones.feature.forum.domain.deadline
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsingService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowseQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsePage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCategory
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumFeedFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentOrder
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumListQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPartFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostDetail
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostSummary
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVote
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchOrder
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSubCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteSelection

enum class OfficialForumLoadStatus { Idle, Loading, Loaded, Failed }

data class OfficialForumListUiState(
    val contentKind: OfficialForumContentKind = OfficialForumContentKind.Post,
    val posts: List<OfficialForumPostSummary> = emptyList(),
    val parts: List<OfficialForumPartFilter> = emptyList(),
    val selectedPartIds: Set<Int> = emptySet(),
    val selectedPostId: Int? = null,
    val searchText: String = "",
    val loadedSearchText: String = "",
    val searchOrder: OfficialForumSearchOrder = OfficialForumSearchOrder.Time,
    val page: Int = 1,
    val total: Int = 0,
    val status: OfficialForumLoadStatus = OfficialForumLoadStatus.Idle,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
)

data class OfficialForumBrowsingUiState(
    val available: Boolean = false,
    val categories: List<OfficialForumCategory> = emptyList(),
    val categoryStatus: OfficialForumLoadStatus = OfficialForumLoadStatus.Idle,
    val filter: OfficialForumFeedFilter = OfficialForumFeedFilter.Default,
)

class OfficialForumListViewModel(private val service: OfficialForumService) : ViewModel() {
    private val browsingService = service as? OfficialForumBrowsingService
    private val mutableState = MutableStateFlow(OfficialForumListUiState())
    private val mutableBrowsingState = MutableStateFlow(
        OfficialForumBrowsingUiState(available = browsingService != null),
    )
    private val categoryCache = mutableMapOf<OfficialForumContentKind, List<OfficialForumCategory>>()
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0L
    private var pageTime: String? = null
    private var loadedQuery: OfficialForumListUiState? = null
    val state: StateFlow<OfficialForumListUiState> = mutableState.asStateFlow()
    val browsingState: StateFlow<OfficialForumBrowsingUiState> = mutableBrowsingState.asStateFlow()

    fun ensureLoaded() {
        if (mutableState.value.status == OfficialForumLoadStatus.Idle) refresh()
    }

    fun setContentKind(kind: OfficialForumContentKind) {
        if (mutableState.value.contentKind == kind) return
        mutableState.update {
            it.copy(contentKind = kind, selectedPartIds = emptySet(), parts = emptyList(),
                searchText = "", loadedSearchText = "")
        }
        mutableBrowsingState.update {
            it.copy(filter = OfficialForumFeedFilter.Default, categories = emptyList(),
                categoryStatus = OfficialForumLoadStatus.Idle)
        }
        restart(clearContent = true)
    }

    fun setSearchText(value: String) {
        val clearSearch = value.isBlank() && mutableState.value.loadedSearchText.isNotEmpty()
        mutableState.update { it.copy(searchText = value) }
        if (clearSearch) restart(clearContent = true)
    }

    fun submitSearch() {
        val normalized = mutableState.value.searchText.trim()
        if (normalized.isEmpty()) return
        mutableState.update { it.copy(searchText = normalized) }
        restart(clearContent = normalized != mutableState.value.loadedSearchText)
    }

    fun applyFilters(partIds: Set<Int>, order: OfficialForumSearchOrder) {
        val current = mutableState.value
        if (current.selectedPartIds == partIds && current.searchOrder == order) return
        mutableState.update { it.copy(selectedPartIds = partIds, searchOrder = order) }
        restart(clearContent = true)
    }

    fun togglePart(id: Int) {
        mutableState.update { current ->
            val selected = if (current.contentKind == OfficialForumContentKind.Guide) {
                if (current.selectedPartIds == setOf(id)) emptySet() else setOf(id)
            } else current.selectedPartIds.toMutableSet().apply { if (!add(id)) remove(id) }
            current.copy(selectedPartIds = selected)
        }
        restart(clearContent = true)
    }

    fun clearParts() {
        if (mutableState.value.selectedPartIds.isEmpty()) return
        mutableState.update { it.copy(selectedPartIds = emptySet()) }
        restart(clearContent = true)
    }

    fun setFeedFilter(filter: OfficialForumFeedFilter) {
        if (browsingService == null || mutableBrowsingState.value.filter == filter) return
        mutableBrowsingState.update { it.copy(filter = filter) }
        restart(clearContent = true)
    }

    fun setSearchOrder(order: OfficialForumSearchOrder) {
        if (mutableState.value.searchOrder == order) return
        mutableState.update { it.copy(searchOrder = order) }
        if (mutableState.value.loadedSearchText.isNotEmpty()) restart(clearContent = true)
    }

    fun refresh() = restart(clearContent =
        mutableState.value.searchText.trim() != mutableState.value.loadedSearchText)

    private fun restart(clearContent: Boolean) {
        val requestGeneration = ++generation
        refreshJob?.cancel()
        pageJob?.cancel()
        if (clearContent) {
            pageTime = null
            loadedQuery = null
            mutableState.update {
                it.copy(posts = emptyList(), selectedPostId = null, page = 1, total = 0)
            }
        }
        mutableState.update {
            it.copy(status = OfficialForumLoadStatus.Loading, isLoadingMore = false,
                loadMoreFailed = false)
        }
        val snapshot = mutableState.value
        val filter = mutableBrowsingState.value.filter
        refreshJob = viewModelScope.launch {
            coroutineScope {
                val categories = launch { loadCategories(snapshot.contentKind, requestGeneration) }
                try {
                    val result = fetchPage(snapshot, snapshot.searchText.trim(), 1, null, filter)
                    if (generation != requestGeneration) return@coroutineScope
                    pageTime = result.pageTime
                    loadedQuery = snapshot.copy(loadedSearchText = snapshot.searchText.trim())
                    mutableState.update { current ->
                        current.copy(posts = result.page.items,
                            loadedSearchText = snapshot.searchText.trim(),
                            page = result.page.page, total = result.page.total,
                            status = OfficialForumLoadStatus.Loaded)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == requestGeneration) {
                        mutableState.update { it.copy(status = OfficialForumLoadStatus.Failed) }
                    }
                }
                categories.join()
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        val snapshot = loadedQuery ?: return
        if (current.status != OfficialForumLoadStatus.Loaded || current.isLoadingMore ||
            current.posts.size >= current.total) return
        val requestGeneration = generation
        val cursor = pageTime
        val filter = mutableBrowsingState.value.filter
        mutableState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        pageJob = viewModelScope.launch {
            try {
                val result = fetchPage(snapshot, snapshot.loadedSearchText, current.page + 1, cursor, filter)
                if (generation != requestGeneration) return@launch
                pageTime = result.pageTime ?: cursor
                mutableState.update { latest ->
                    val page = result.page
                    val merged = (latest.posts + page.items).distinctBy(OfficialForumPostSummary::id)
                    latest.copy(posts = merged, page = page.page,
                        total = if (page.items.size < PageSize || merged.size == latest.posts.size) {
                            merged.size
                        } else maxOf(page.total, merged.size + 1), isLoadingMore = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == requestGeneration) {
                    mutableState.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
                }
            }
        }
    }

    fun select(post: OfficialForumPostSummary) = selectPostId(post.id)

    fun selectPostId(postId: Int) {
        require(postId > 0) { "postId must be positive" }
        mutableState.update { it.copy(selectedPostId = postId) }
    }

    fun clearSelection() { mutableState.update { it.copy(selectedPostId = null) } }

    private suspend fun loadCategories(kind: OfficialForumContentKind, requestGeneration: Long) {
        mutableBrowsingState.update { it.copy(categoryStatus = OfficialForumLoadStatus.Loading) }
        try {
            val categories = categoryCache[kind] ?: when {
                browsingService != null -> browsingService.fetchCategories(kind)
                kind == OfficialForumContentKind.Post -> service.fetchParts().map { OfficialForumCategory(it) }
                else -> emptyList()
            }.also { categoryCache[kind] = it }
            if (generation != requestGeneration) return
            mutableBrowsingState.update {
                it.copy(categories = categories, categoryStatus = OfficialForumLoadStatus.Loaded)
            }
            mutableState.update { it.copy(parts = categories.map { category -> category.part }) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (generation == requestGeneration) {
                mutableBrowsingState.update { it.copy(categoryStatus = OfficialForumLoadStatus.Failed) }
            }
        }
    }

    private suspend fun fetchPage(
        state: OfficialForumListUiState,
        search: String,
        page: Int,
        cursor: String?,
        filter: OfficialForumFeedFilter,
    ): OfficialForumBrowsePage {
        val parts = state.selectedPartIds.sorted()
        return if (search.isNotEmpty()) {
            val query = OfficialForumSearchQuery(state.contentKind, search, parts,
                state.searchOrder, page, PageSize)
            browsingService?.searchBrowsePage(query, cursor)
                ?: OfficialForumBrowsePage(service.searchPosts(query), null)
        } else {
            val query = OfficialForumListQuery(state.contentKind, page, PageSize, parts)
            browsingService?.fetchBrowsePage(OfficialForumBrowseQuery(query, filter, cursor))
                ?: OfficialForumBrowsePage(service.fetchPosts(query), null)
        }
    }

    private companion object { const val PageSize = 20 }
}

data class OfficialForumDetailUiState(
    val detail: OfficialForumPostDetail? = null,
    val comments: List<OfficialForumComment> = emptyList(),
    val commentOrder: OfficialForumCommentOrder = OfficialForumCommentOrder.Hottest,
    val onlyPostAuthor: Boolean = false,
    val commentPage: Int = 1,
    val commentTotal: Int = 0,
    val status: OfficialForumLoadStatus = OfficialForumLoadStatus.Loading,
    val commentsStatus: OfficialForumLoadStatus = OfficialForumLoadStatus.Idle,
    val isLoadingMoreComments: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val selectedSubCommentRootId: Int? = null,
    val subCommentsByRootId: Map<Int, List<OfficialForumComment>> = emptyMap(),
    val subCommentTotals: Map<Int, Int> = emptyMap(),
    val loadingSubCommentIds: Set<Int> = emptySet(),
    val subCommentErrorIds: Set<Int> = emptySet(),
    val isLikingPost: Boolean = false,
    val isStarringPost: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val submittingVoteIds: Set<String> = emptySet(),
    val deletingCommentIds: Set<Int> = emptySet(),
    val likingCommentIds: Set<Int> = emptySet(),
    val actionFailed: Boolean = false,
)

data class OfficialForumReplyTarget(
    val parentId: Int,
    val rootParentId: Int,
    val authorName: String? = null,
)

enum class OfficialForumInteractionError {
    Unavailable, AuthenticationRequired, InvalidInput, ImageUploadFailed, Failed,
}

data class OfficialForumDetailInteractionState(
    val isComposerOpen: Boolean = false,
    val replyTarget: OfficialForumReplyTarget? = null,
    val commentText: String = "",
    val commentImage: OfficialForumCommentImageUpload? = null,
    val commentSuccessRevision: Long = 0,
    val commentError: OfficialForumInteractionError? = null,
    val actionError: OfficialForumInteractionError? = null,
    val voteSelections: Map<String, Set<Int>> = emptyMap(),
    val voteErrors: Map<String, OfficialForumInteractionError> = emptyMap(),
    val voteResultsAvailable: Set<String> = emptySet(),
)

class OfficialForumDetailViewModel(
    private val service: OfficialForumService,
    private val postId: Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfficialForumDetailUiState())
    private val mutableInteractionState = MutableStateFlow(OfficialForumDetailInteractionState())
    private val mutableCommentEditorState = MutableStateFlow(OfficialForumCommentEditorState())
    private var mentionCandidatesJob: Job? = null
    private var mentionCandidatesGeneration = 0L
    private var hasLoadedMentionCandidates = false
    private var loadJob: Job? = null
    private var commentsJob: Job? = null
    private var pageJob: Job? = null
    private val subCommentJobs = mutableMapOf<Int, Job>()
    private val writeJobs = mutableSetOf<Job>()
    private var detailGeneration = 0L
    private var commentGeneration = 0L
    private var interactionGeneration = 0L
    private var commentDraftGeneration = 0L
    private var isCleared = false
    private var loadedCommentQuery: OfficialForumCommentQuery? = null
    private val expandedSubCommentIds = mutableSetOf<Int>()
    private val completedVotes = mutableMapOf<String, OfficialForumPostVote>()
    private var uploadedImage: OfficialForumCommentImageUpload? = null
    private var uploadedImageUrl: String? = null
    val state: StateFlow<OfficialForumDetailUiState> = mutableState.asStateFlow()
    val interactionState: StateFlow<OfficialForumDetailInteractionState> = mutableInteractionState.asStateFlow()
    val commentEditorState: StateFlow<OfficialForumCommentEditorState> = mutableCommentEditorState.asStateFlow()
    val canMention: Boolean get() = (service as? OfficialForumCommentAuthoringService)?.canReadMentionCandidates == true
    val canWrite: Boolean get() = service.canPerformAuthenticatedWrites
    val canUploadCommentImages: Boolean
        get() = (service as? OfficialForumImageUploadService)?.canUploadCommentImages == true
    /** Eligibility permits an explicit user action; it does not report a verified write capability. */
    val canInteract: Boolean get() = !isCleared && (canWrite ||
        (service as? OfficialForumActionEligibilityService)?.canAttemptAuthenticatedWrites == true)
    /** Selecting an image is local. Upload verification occurs only when sending the comment. */
    val canAttachCommentImage: Boolean get() = canInteract && (canUploadCommentImages ||
        (service as? OfficialForumActionEligibilityService)?.canAttemptCommentImageUpload == true)
    /** Invalidates local image reads when a draft or its credential ownership is cleared. */
    val commentDraftRevision: Long get() = commentDraftGeneration
    private var previouslyCouldInteract = canInteract
    private var previouslyCouldAttachImage = canAttachCommentImage

    init { load() }

    /** Hosts call this when their observable session eligibility changes, including while idle. */
    fun synchronizeActionEligibility() { clearRevokedActionEligibility() }

    private fun clearRevokedActionEligibility(): Boolean {
        val interact = canInteract
        val attachImage = canAttachCommentImage
        val revoked = previouslyCouldInteract && !interact || previouslyCouldAttachImage && !attachImage
        previouslyCouldInteract = interact
        previouslyCouldAttachImage = attachImage
        if (revoked) clearProtectedContent()
        return revoked
    }

    fun load() {
        if (isCleared) return
        val generation = ++detailGeneration
        loadJob?.cancel()
        mutableState.update { it.copy(status = OfficialForumLoadStatus.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val response = (service as? OfficialForumInteractionService)?.fetchPostInteraction(postId)
                    ?: OfficialForumPostInteraction(service.fetchPostDetail(postId), emptySet())
                currentCoroutineContext().ensureActive()
                if (generation != detailGeneration) return@launch
                val detail = response.detail
                detail.votes.filter { it.hasParticipated || it.id in response.voteResultsAvailable }
                    .forEach { completedVotes.remove(it.id) }
                mutableState.update {
                    it.copy(detail = detail.copy(votes = detail.votes.map { vote -> completedVotes[vote.id] ?: vote }),
                        status = OfficialForumLoadStatus.Loaded)
                }
                mutableInteractionState.update { interaction -> interaction.copy(
                    voteResultsAvailable = response.voteResultsAvailable,
                    voteSelections = interaction.voteSelections.mapNotNull { (id, selection) ->
                        val vote = currentVote(id)
                        if (vote == null || vote.hasParticipated || id in response.voteResultsAvailable ||
                            id in completedVotes || vote.deadline()?.let { Instant.now().isAfter(it) } == true) null
                        else id to selection.filter { selected -> vote.options.any { it.optionId == selected } }.toSet()
                    }.toMap(),
                ) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == detailGeneration) {
                    if (error is OfficialForumException.AuthenticationRequired) clearProtectedContent()
                    mutableState.update { it.copy(status = OfficialForumLoadStatus.Failed) }
                }
            } finally {
                if (generation == detailGeneration && mutableState.value.status == OfficialForumLoadStatus.Loading) {
                    mutableState.update { it.copy(status = if (it.detail == null) OfficialForumLoadStatus.Idle else OfficialForumLoadStatus.Loaded) }
                }
            }
        }
        restartComments(clearContent = false)
    }

    fun setCommentOrder(order: OfficialForumCommentOrder) {
        if (isCleared) return
        if (mutableState.value.commentOrder == order) return
        mutableState.update { it.copy(commentOrder = order) }
        restartComments(clearContent = true)
    }

    fun toggleOnlyPostAuthor() {
        if (isCleared) return
        mutableState.update { it.copy(onlyPostAuthor = !it.onlyPostAuthor) }
        restartComments(clearContent = true)
    }

    fun refreshComments(force: Boolean = false) {
        if (isCleared) return
        if (!force && commentsJob?.isActive == true && mutableState.value.commentsStatus == OfficialForumLoadStatus.Loading) return
        restartComments(clearContent = false)
    }

    private fun invalidateCommentReads(): Long {
        val generation = ++commentGeneration
        commentsJob?.cancel()
        pageJob?.cancel()
        subCommentJobs.values.forEach(Job::cancel)
        subCommentJobs.clear()
        expandedSubCommentIds.clear()
        mutableState.update { it.copy(isLoadingMoreComments = false, loadingSubCommentIds = emptySet()) }
        return generation
    }

    private fun restartComments(clearContent: Boolean) {
        val generation = invalidateCommentReads()
        val query = commentQuery(1)
        if (clearContent) loadedCommentQuery = null
        mutableState.update {
            if (clearContent) it.copy(comments = emptyList(), commentPage = 1, commentTotal = 0,
                commentsStatus = OfficialForumLoadStatus.Loading, loadMoreFailed = false,
                selectedSubCommentRootId = null, subCommentsByRootId = emptyMap(), subCommentTotals = emptyMap(),
                subCommentErrorIds = emptySet())
            else it.copy(commentsStatus = OfficialForumLoadStatus.Loading, loadMoreFailed = false)
        }
        commentsJob = viewModelScope.launch {
            try {
                val page = service.fetchComments(query)
                currentCoroutineContext().ensureActive()
                if (generation != commentGeneration) return@launch
                loadedCommentQuery = query
                val comments = page.items.distinctBy(OfficialForumComment::id)
                mutableState.update {
                    it.copy(comments = comments, commentPage = page.page, commentTotal = page.total,
                        commentsStatus = OfficialForumLoadStatus.Loaded, subCommentsByRootId = emptyMap(),
                        subCommentTotals = emptyMap(), subCommentErrorIds = emptySet(),
                        selectedSubCommentRootId = it.selectedSubCommentRootId?.takeIf { id -> comments.any { c -> c.id == id } })
                }
                comments.firstOrNull { it.id == mutableState.value.selectedSubCommentRootId }
                    ?.takeIf { it.childCount > 0 }?.let(::loadAllSubComments)
                loadPreviews(comments, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == commentGeneration) {
                    if (error is OfficialForumException.AuthenticationRequired) clearProtectedContent()
                    mutableState.update { it.copy(commentsStatus = OfficialForumLoadStatus.Failed) }
                }
            } finally {
                if (generation == commentGeneration && mutableState.value.commentsStatus == OfficialForumLoadStatus.Loading) {
                    mutableState.update { it.copy(commentsStatus = if (loadedCommentQuery == null) OfficialForumLoadStatus.Idle else OfficialForumLoadStatus.Loaded) }
                }
            }
        }
    }

    fun loadMoreComments() {
        if (isCleared) return
        val snapshot = mutableState.value
        val query = loadedCommentQuery ?: return
        if (snapshot.commentsStatus == OfficialForumLoadStatus.Loading || snapshot.isLoadingMoreComments ||
            snapshot.comments.size >= snapshot.commentTotal) return
        val generation = commentGeneration
        mutableState.update { it.copy(isLoadingMoreComments = true, loadMoreFailed = false) }
        pageJob = viewModelScope.launch {
            try {
                val page = service.fetchComments(query.copy(page = snapshot.commentPage + 1))
                currentCoroutineContext().ensureActive()
                if (generation != commentGeneration) return@launch
                val existingIds = mutableState.value.comments.map { it.id }.toSet()
                val added = page.items.distinctBy(OfficialForumComment::id).filterNot { it.id in existingIds }
                mutableState.update { current ->
                    val merged = (current.comments + added).distinctBy(OfficialForumComment::id)
                    current.copy(comments = merged, commentPage = page.page,
                        commentTotal = if (added.isEmpty() || page.items.size < CommentPageSize) merged.size
                            else maxOf(page.total, merged.size + 1), isLoadingMoreComments = false)
                }
                loadPreviews(added, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == commentGeneration) {
                    if (error is OfficialForumException.AuthenticationRequired) clearProtectedContent()
                    mutableState.update { it.copy(loadMoreFailed = true) }
                }
            } finally {
                if (generation == commentGeneration) mutableState.update { it.copy(isLoadingMoreComments = false) }
            }
        }
    }

    fun openSubComments(comment: OfficialForumComment) {
        if (isCleared) return
        val current = mutableState.value.comments.firstOrNull { it.id == comment.id } ?: return
        mutableState.update { it.copy(selectedSubCommentRootId = current.id) }
        if (mutableState.value.subCommentsByRootId[current.id].orEmpty().size < current.childCount) {
            loadAllSubComments(current)
        }
    }

    fun dismissSubComments() { mutableState.update { it.copy(selectedSubCommentRootId = null) } }

    fun openCommentComposer(target: OfficialForumReplyTarget = OfficialForumReplyTarget(0, 0)) {
        if (mutableState.value.isSubmittingComment || !requireWrite()) return
        if (!validTarget(target)) { commentError(OfficialForumInteractionError.InvalidInput); return }
        mutableInteractionState.update { it.copy(isComposerOpen = true, replyTarget = target, commentError = null) }
    }

    fun resumeCommentComposer() {
        if (mutableState.value.isSubmittingComment || !requireWrite()) return
        val target = mutableInteractionState.value.replyTarget ?: OfficialForumReplyTarget(0, 0)
        mutableInteractionState.update {
            it.copy(isComposerOpen = true, replyTarget = target,
                commentError = if (validTarget(target)) null else OfficialForumInteractionError.InvalidInput)
        }
    }

    fun closeCommentComposer() {
        if (!mutableState.value.isSubmittingComment) {
            closeCommentEmojiPicker()
            closeCommentMentionPicker()
            mutableInteractionState.update { it.copy(isComposerOpen = false) }
        }
    }

    fun discardCommentDraft() {
        if (mutableState.value.isSubmittingComment) return
        clearDraft()
    }

    fun updateCommentDraft(text: String) {
        updateCommentEditor(text, text.length, text.length)
    }

    fun updateCommentEditor(text: String, selectionStart: Int, selectionEnd: Int,
        change: OfficialForumCommentTextChange? = null) {
        if (isCleared || mutableState.value.isSubmittingComment) return
        if (clearRevokedActionEligibility() || clearRevokedCommentAuthoring() || !requireWrite()) return
        mutableCommentEditorState.update { it.edit(text, selectionStart, selectionEnd, change) }
        mutableInteractionState.update { it.copy(commentText = text, commentError = null) }
    }

    fun openCommentEmojiPicker() {
        if (!canEditComment()) return
        closeCommentMentionPicker()
        mutableCommentEditorState.update { it.copy(isEmojiPickerOpen = true) }
    }

    fun closeCommentEmojiPicker() {
        mutableCommentEditorState.update { it.copy(isEmojiPickerOpen = false) }
    }

    fun insertCommentEmoji(number: Int) {
        if (number !in OfficialForumCommentEmojiNumbers || !canEditComment()) return
        mutableCommentEditorState.update { it.insert("[emo$number]").copy(isEmojiPickerOpen = false) }
        syncCommentEditorText()
    }

    fun openCommentMentionPicker() {
        if (!canEditComment() || !requireMentionRead()) return
        closeCommentEmojiPicker()
        mutableCommentEditorState.update { it.copy(isMentionPickerOpen = true) }
        if (!hasLoadedMentionCandidates) refreshCommentMentionCandidates()
    }

    fun closeCommentMentionPicker() {
        ++mentionCandidatesGeneration
        mentionCandidatesJob?.cancel()
        mentionCandidatesJob = null
        mutableCommentEditorState.update { it.copy(isMentionPickerOpen = false,
            candidateStatus = if (it.candidateStatus == OfficialForumLoadStatus.Loading) {
                if (hasLoadedMentionCandidates) OfficialForumLoadStatus.Loaded else OfficialForumLoadStatus.Idle
            } else it.candidateStatus) }
    }

    fun updateCommentMentionQuery(query: String) {
        if (isCleared || mutableState.value.isSubmittingComment || !requireWrite()) return
        mutableCommentEditorState.update { it.copy(candidateQuery = query) }
    }

    fun refreshCommentMentionCandidates() {
        if (!mutableCommentEditorState.value.isMentionPickerOpen || !canEditComment() || !requireMentionRead() ||
            mentionCandidatesJob?.isActive == true) return
        val authoring = service as? OfficialForumCommentAuthoringService ?: return
        val generation = ++mentionCandidatesGeneration
        mutableCommentEditorState.update { it.copy(candidateStatus = OfficialForumLoadStatus.Loading, candidateError = null) }
        mentionCandidatesJob = viewModelScope.launch {
            try {
                val candidates = authoring.fetchMentionCandidates()
                currentCoroutineContext().ensureActive()
                if (generation != mentionCandidatesGeneration) return@launch
                if (!canInteract || !canMention) throw OfficialForumException.AuthenticationRequired
                hasLoadedMentionCandidates = true
                mutableCommentEditorState.update { it.copy(
                    candidates = candidates.filter { candidate ->
                        OfficialForumCommentMention(candidate.uuid, candidate.name).isEncodable()
                    }.distinctBy { candidate -> candidate.uuid to candidate.name },
                    candidateStatus = OfficialForumLoadStatus.Loaded, candidateError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != mentionCandidatesGeneration) return@launch
                if (error is OfficialForumException.AuthenticationRequired) {
                    clearProtectedContent()
                    commentError(OfficialForumInteractionError.AuthenticationRequired)
                } else mutableCommentEditorState.update { it.copy(candidateStatus = OfficialForumLoadStatus.Failed,
                    candidateError = OfficialForumInteractionError.Failed) }
            } finally {
                if (generation == mentionCandidatesGeneration &&
                    mutableCommentEditorState.value.candidateStatus == OfficialForumLoadStatus.Loading) {
                    mutableCommentEditorState.update { it.copy(candidateStatus =
                        if (hasLoadedMentionCandidates) OfficialForumLoadStatus.Loaded else OfficialForumLoadStatus.Idle) }
                }
            }
        }
    }

    fun selectCommentMention(candidate: OfficialForumMentionCandidate) {
        if (!mutableCommentEditorState.value.isMentionPickerOpen || !canEditComment() || !requireMentionRead()) return
        val selected = mutableCommentEditorState.value.candidates.firstOrNull {
            it.uuid == candidate.uuid && it.name == candidate.name
        } ?: return
        val mention = OfficialForumCommentMention(selected.uuid, selected.name)
        if (!mention.isEncodable()) return
        mutableCommentEditorState.update { it.insert("@${selected.name} ", mention) }
        closeCommentMentionPicker()
        syncCommentEditorText()
    }

    private fun canEditComment(): Boolean = !isCleared && !mutableState.value.isSubmittingComment &&
        mutableInteractionState.value.isComposerOpen && !clearRevokedCommentAuthoring() && requireWrite()

    private fun clearRevokedCommentAuthoring(): Boolean {
        val editor = mutableCommentEditorState.value
        if (canMention || editor.candidates.isEmpty() && editor.mentions.isEmpty() &&
            !editor.isMentionPickerOpen && editor.candidateStatus == OfficialForumLoadStatus.Idle) return false
        clearProtectedContent()
        commentError(OfficialForumInteractionError.Unavailable)
        return true
    }

    private fun requireMentionRead(): Boolean {
        if (canMention) return true
        if (service is OfficialForumCommentAuthoringService) clearProtectedContent()
        commentError(OfficialForumInteractionError.Unavailable)
        return false
    }

    private fun syncCommentEditorText() {
        mutableInteractionState.update { it.copy(commentText = mutableCommentEditorState.value.text, commentError = null) }
    }

    private fun clearCommentEditor() {
        closeCommentMentionPicker()
        hasLoadedMentionCandidates = false
        mutableCommentEditorState.value = OfficialForumCommentEditorState()
    }

    fun setCommentImage(image: OfficialForumCommentImageUpload?) {
        if (isCleared || mutableState.value.isSubmittingComment) return
        if (clearRevokedActionEligibility()) return
        if (image != null && (!requireWrite() || !canAttachCommentImage)) {
            commentError(OfficialForumInteractionError.Unavailable)
            return
        }
        if (image != null && image.bytes.isEmpty()) { commentError(OfficialForumInteractionError.InvalidInput); return }
        if (!sameImage(image, mutableInteractionState.value.commentImage)) clearUploadedImage()
        mutableInteractionState.update { it.copy(commentImage = image?.copy(bytes = image.bytes.copyOf()), commentError = null) }
    }

    fun submitDraftComment() {
        val draft = mutableInteractionState.value
        submitPreparedComment(draft.replyTarget ?: OfficialForumReplyTarget(0, 0), draft.commentText,
            draft.commentImage, mutableCommentEditorState.value.prepareComment())
    }

    fun submitComment(target: OfficialForumReplyTarget, text: String,
        image: OfficialForumCommentImageUpload? = null, onSuccess: () -> Unit = {}) {
        // This original API always accepts plain text; matching names never infer notification identities.
        submitPreparedComment(target, text, image, null, onSuccess)
    }

    private fun submitPreparedComment(target: OfficialForumReplyTarget, text: String,
        image: OfficialForumCommentImageUpload?, prepared: OfficialForumPreparedComment?, onSuccess: () -> Unit = {}) {
        if (isCleared || mutableState.value.isSubmittingComment) return
        if (clearRevokedActionEligibility() || clearRevokedCommentAuthoring()) return
        if (!requireWrite()) { commentError(OfficialForumInteractionError.Unavailable); return }
        val content = prepared?.html ?: text.officialForumCommentHtml()
        val mentions = prepared?.mentions.orEmpty()
        if (mentions.isNotEmpty() && (service !is OfficialForumCommentAuthoringService || !canMention)) {
            clearProtectedContent()
            commentError(OfficialForumInteractionError.Unavailable); return
        }
        if ((content.isEmpty() && image == null) || !validTarget(target) || image?.bytes?.isEmpty() == true) {
            commentError(OfficialForumInteractionError.InvalidInput); return
        }
        if (image != null && !canAttachCommentImage) { commentError(OfficialForumInteractionError.Unavailable); return }
        if (prepared == null) {
            closeCommentEmojiPicker()
            closeCommentMentionPicker()
            mutableCommentEditorState.update { it.copy(text = text, selectionStart = text.length,
                selectionEnd = text.length, mentions = emptyList()) }
        }
        val stableImage = image?.copy(bytes = image.bytes.copyOf())
        if (!sameImage(stableImage, uploadedImage)) clearUploadedImage()
        mutableInteractionState.update { it.copy(replyTarget = target, commentText = text, commentImage = stableImage,
            commentError = null, actionError = null) }
        mutableState.update { it.copy(isSubmittingComment = true, actionFailed = false) }
        launchWrite(comment = true, finish = { it.copy(isSubmittingComment = false) }) { generation ->
            val picture = if (stableImage == null) "" else uploadedImageUrl ?: service.uploadCommentImage(stableImage).also {
                if (!activeWrite(generation)) return@launchWrite
                uploadedImage = stableImage
                uploadedImageUrl = it
            }
            if (!activeWrite(generation)) return@launchWrite
            if (stableImage != null && !canAttachCommentImage) throw OfficialForumException.AuthenticationRequired
            val submission = OfficialForumCommentDraft(postId, target.parentId, target.rootParentId, content, picture)
            if (mentions.isEmpty()) service.submitComment(submission)
            else {
                if (!canMention) throw OfficialForumException.AuthenticationRequired
                (service as OfficialForumCommentAuthoringService).submitCommentWithMentions(submission, mentions)
            }
            if (!activeWrite(generation)) return@launchWrite
            invalidateDetailRead()
            mutableState.update { current -> current.copy(detail = current.detail?.copy(commentCount = current.detail.commentCount + 1)) }
            val revision = mutableInteractionState.value.commentSuccessRevision + 1
            clearDraft()
            mutableInteractionState.update { it.copy(commentSuccessRevision = revision) }
            restartComments(clearContent = false)
            // Legacy callbacks remain supported; committed success is already recorded in state.
            try { onSuccess() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
    }

    fun likePost() {
        val detail = mutableState.value.detail ?: return
        if (mutableState.value.isLikingPost || !requireWrite()) return
        mutableState.update { it.copy(isLikingPost = true, actionFailed = false) }
        launchWrite(finish = { it.copy(isLikingPost = false) }) { generation ->
            val value = service.likePost(detail.id)
            if (!activeWrite(generation)) return@launchWrite
            invalidateDetailRead()
            mutableState.update { it.copy(detail = it.detail?.applyingLike(value)) }
        }
    }

    fun starPost() {
        val detail = mutableState.value.detail ?: return
        if (mutableState.value.isStarringPost || !requireWrite()) return
        mutableState.update { it.copy(isStarringPost = true, actionFailed = false) }
        launchWrite(finish = { it.copy(isStarringPost = false) }) { generation ->
            val value = service.starPost(detail.id)
            if (!activeWrite(generation)) return@launchWrite
            invalidateDetailRead()
            mutableState.update { it.copy(detail = it.detail?.applyingStar(value)) }
        }
    }

    fun deleteComment(comment: OfficialForumComment) {
        val known = findComment(comment.id) ?: return
        if (!known.isMine || known.id in mutableState.value.deletingCommentIds || !requireWrite()) return
        mutableState.update { it.copy(deletingCommentIds = it.deletingCommentIds + known.id, actionFailed = false) }
        launchWrite(finish = { it.copy(deletingCommentIds = it.deletingCommentIds - known.id) }) { generation ->
            service.deleteComment(known.id)
            if (!activeWrite(generation)) return@launchWrite
            invalidateDetailRead()
            invalidateCommentReads()
            mutableState.update { it.removingComment(known.id).copy(commentsStatus = OfficialForumLoadStatus.Loaded) }
        }
    }

    fun likeComment(comment: OfficialForumComment) {
        val known = findComment(comment.id) ?: return
        if (known.id in mutableState.value.likingCommentIds || !requireWrite()) return
        mutableState.update { it.copy(likingCommentIds = it.likingCommentIds + known.id, actionFailed = false) }
        launchWrite(finish = { it.copy(likingCommentIds = it.likingCommentIds - known.id) }) { generation ->
            val value = service.likeComment(known.id)
            if (!activeWrite(generation)) return@launchWrite
            invalidateCommentReads()
            mutableState.update { it.applyingCommentLike(known.id, value).copy(commentsStatus = OfficialForumLoadStatus.Loaded) }
        }
    }

    fun setVoteSelection(voteId: String, optionIds: Set<Int>) {
        val vote = currentVote(voteId) ?: return
        if (isCleared || !isVoteOpen(vote) || voteId in mutableState.value.submittingVoteIds || !requireWrite()) return
        if (optionIds.any { id -> vote.options.none { it.optionId == id } }) {
            voteError(voteId, OfficialForumInteractionError.InvalidInput); return
        }
        mutableInteractionState.update { it.copy(voteSelections = it.voteSelections + (voteId to optionIds.toSet()),
            voteErrors = it.voteErrors - voteId) }
    }

    fun submitSelectedVote(voteId: String) {
        val vote = currentVote(voteId) ?: return
        submitVote(vote, mutableInteractionState.value.voteSelections[voteId].orEmpty())
    }

    fun submitVote(vote: OfficialForumPostVote, optionIds: Set<Int>) {
        val current = currentVote(vote.id) ?: return
        if (isCleared || !isVoteOpen(current) || current.id in mutableState.value.submittingVoteIds) return
        if (!requireWrite()) { voteError(current.id, OfficialForumInteractionError.Unavailable); return }
        val selected = current.options.filter { it.optionId in optionIds }
        val minimum = if (current.allowsMultipleSelection) maxOf(current.minimumSelectionCount ?: 1, 1) else 1
        val maximum = if (current.allowsMultipleSelection) current.maximumSelectionCount else 1
        if (selected.size != optionIds.size || selected.size < minimum || maximum != null && selected.size > maximum) {
            voteError(current.id, OfficialForumInteractionError.InvalidInput); return
        }
        mutableInteractionState.update { it.copy(voteSelections = it.voteSelections + (current.id to optionIds.toSet()),
            voteErrors = it.voteErrors - current.id) }
        mutableState.update { it.copy(submittingVoteIds = it.submittingVoteIds + current.id, actionFailed = false) }
        launchWrite(voteId = current.id, finish = { it.copy(submittingVoteIds = it.submittingVoteIds - current.id) }) { generation ->
            val result = service.submitVote(OfficialForumVoteDraft(postId, selected.map { OfficialForumVoteSelection(it.optionId, it.title) }))
            if (!activeWrite(generation)) return@launchWrite
            invalidateDetailRead()
            val updated = current.copy(totalUserCount = result.voteTotalUser, options = current.options.map {
                it.copy(totalVoteCount = result.voteDetails[it.optionId] ?: it.totalVoteCount, isParticipant = it.optionId in optionIds)
            })
            completedVotes[current.id] = updated
            mutableState.update { it.copy(detail = it.detail?.copy(votes = it.detail.votes.map { vote -> if (vote.id == current.id) updated else vote })) }
            mutableInteractionState.update { it.copy(voteSelections = it.voteSelections - current.id) }
        }
    }

    fun clearInteractionErrors() {
        mutableInteractionState.update { it.copy(commentError = null, actionError = null, voteErrors = emptyMap()) }
        mutableState.update { it.copy(actionFailed = false) }
    }

    fun clearProtectedContent() {
        ++commentDraftGeneration
        ++interactionGeneration
        writeJobs.toList().forEach(Job::cancel)
        writeJobs.clear()
        invalidateDetailRead()
        invalidateCommentReads()
        completedVotes.clear()
        clearCommentEditor()
        clearUploadedImage()
        mutableInteractionState.value = OfficialForumDetailInteractionState()
        mutableState.update { current ->
            current.copy(detail = current.detail?.copy(isLiked = null, isStarred = null,
                votes = current.detail.votes.map { vote -> vote.copy(options = vote.options.map { it.copy(isParticipant = false) }) }),
                comments = current.comments.map { it.withoutIdentity() },
                subCommentsByRootId = current.subCommentsByRootId.mapValues { (_, comments) -> comments.map { it.withoutIdentity() } },
                commentsStatus = if (loadedCommentQuery == null) OfficialForumLoadStatus.Idle else OfficialForumLoadStatus.Loaded,
                isLikingPost = false, isStarringPost = false, isSubmittingComment = false,
                submittingVoteIds = emptySet(), deletingCommentIds = emptySet(), likingCommentIds = emptySet(), actionFailed = false)
        }
    }

    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }

    private fun requireWrite(): Boolean {
        if (isCleared) return false
        val revoked = clearRevokedActionEligibility()
        if (!revoked && canInteract) return true
        if (!revoked) clearProtectedContent()
        mutableInteractionState.update { it.copy(actionError = OfficialForumInteractionError.Unavailable) }
        return false
    }

    private suspend fun activeWrite(generation: Long): Boolean {
        currentCoroutineContext().ensureActive()
        if (generation != interactionGeneration) return false
        if (clearRevokedActionEligibility() || !canInteract) throw OfficialForumException.AuthenticationRequired
        return true
    }

    private fun launchWrite(comment: Boolean = false, voteId: String? = null,
        finish: (OfficialForumDetailUiState) -> OfficialForumDetailUiState,
        block: suspend (Long) -> Unit) {
        val generation = interactionGeneration
        mutableInteractionState.update { it.copy(actionError = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (activeWrite(generation)) block(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != interactionGeneration) return@launch
                val kind = when (error) {
                    OfficialForumException.AuthenticationRequired -> OfficialForumInteractionError.AuthenticationRequired
                    OfficialForumException.ImageUploadFailed -> OfficialForumInteractionError.ImageUploadFailed
                    else -> OfficialForumInteractionError.Failed
                }
                if (kind == OfficialForumInteractionError.AuthenticationRequired) clearProtectedContent()
                mutableState.update { it.copy(actionFailed = true) }
                mutableInteractionState.update { current -> current.copy(actionError = kind,
                    commentError = if (comment) kind else current.commentError,
                    voteErrors = if (voteId == null) current.voteErrors else current.voteErrors + (voteId to kind)) }
            } finally {
                if (generation == interactionGeneration) mutableState.update(finish)
            }
        }
        writeJobs += job
        job.invokeOnCompletion { writeJobs -= job }
        job.start()
    }

    private fun invalidateDetailRead() {
        ++detailGeneration
        loadJob?.cancel()
        mutableState.update { it.copy(status = if (it.detail == null) OfficialForumLoadStatus.Idle else OfficialForumLoadStatus.Loaded) }
    }

    private fun clearDraft() {
        ++commentDraftGeneration
        clearCommentEditor()
        clearUploadedImage()
        mutableInteractionState.update { it.copy(isComposerOpen = false, replyTarget = null, commentText = "",
            commentImage = null, commentError = null) }
    }

    private fun clearUploadedImage() { uploadedImage = null; uploadedImageUrl = null }
    private fun sameImage(a: OfficialForumCommentImageUpload?, b: OfficialForumCommentImageUpload?): Boolean =
        if (a == null || b == null) a == null && b == null else a.mimeType == b.mimeType && a.bytes.contentEquals(b.bytes)
    private fun commentError(error: OfficialForumInteractionError) { mutableInteractionState.update { it.copy(commentError = error) } }
    private fun voteError(id: String, error: OfficialForumInteractionError) { mutableInteractionState.update { it.copy(voteErrors = it.voteErrors + (id to error)) } }
    private fun currentVote(id: String) = mutableState.value.detail?.votes?.firstOrNull { it.id == id }
    private fun isVoteOpen(vote: OfficialForumPostVote): Boolean =
        !vote.hasParticipated && vote.id !in completedVotes &&
            vote.id !in mutableInteractionState.value.voteResultsAvailable &&
            vote.deadline()?.let { Instant.now().isAfter(it) } != true
    private fun findComment(id: Int): OfficialForumComment? = mutableState.value.comments.firstOrNull { it.id == id }
        ?: mutableState.value.subCommentsByRootId.values.flatten().firstOrNull { it.id == id }
    private fun validTarget(target: OfficialForumReplyTarget): Boolean =
        target.parentId == 0 && target.rootParentId == 0 || target.parentId > 0 && target.rootParentId > 0 &&
            mutableState.value.comments.any { it.id == target.rootParentId } &&
            (target.parentId == target.rootParentId || mutableState.value.subCommentsByRootId[target.rootParentId].orEmpty().any { it.id == target.parentId })
    private fun OfficialForumComment.withoutIdentity(): OfficialForumComment = copy(isLiked = false, isMine = false,
        childPreviewComments = childPreviewComments.map { it.withoutIdentity() })

    private fun loadAllSubComments(comment: OfficialForumComment) {
        if (comment.id in mutableState.value.loadingSubCommentIds) return
        val generation = commentGeneration
        expandedSubCommentIds += comment.id
        mutableState.update { it.copy(loadingSubCommentIds = it.loadingSubCommentIds + comment.id,
            subCommentErrorIds = it.subCommentErrorIds - comment.id) }
        subCommentJobs[comment.id] = viewModelScope.launch {
            try {
                val loaded = mutableListOf<OfficialForumComment>()
                var pageNumber = 1
                var total: Int
                do {
                    val page = service.fetchSubComments(OfficialForumSubCommentQuery(comment.id, pageNumber, SubCommentPageSize))
                    currentCoroutineContext().ensureActive()
                    if (generation != commentGeneration) return@launch
                    val existing = loaded.map { it.id }.toSet()
                    val added = page.items.distinctBy(OfficialForumComment::id).filterNot { it.id in existing }
                    loaded += added
                    total = page.total
                    pageNumber++
                    if (added.isEmpty()) break
                } while (loaded.size < total && loaded.size < MaxSubCommentsLoaded)
                mutableState.update { it.copy(subCommentsByRootId = it.subCommentsByRootId + (comment.id to loaded),
                    subCommentTotals = it.subCommentTotals + (comment.id to total), subCommentErrorIds = it.subCommentErrorIds - comment.id) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == commentGeneration) {
                    if (error is OfficialForumException.AuthenticationRequired) clearProtectedContent()
                    mutableState.update { it.copy(subCommentErrorIds = it.subCommentErrorIds + comment.id) }
                }
            } finally {
                if (generation == commentGeneration) mutableState.update { it.copy(loadingSubCommentIds = it.loadingSubCommentIds - comment.id) }
            }
        }
    }

    private suspend fun loadPreviews(comments: List<OfficialForumComment>, generation: Long) {
        val previews = coroutineScope {
            comments.filter { it.childCount > 0 }.map { comment -> async {
                try {
                    val page = service.fetchSubComments(OfficialForumSubCommentQuery(comment.id, limit = PreviewSize))
                    Triple(comment.id, page.items.distinctBy(OfficialForumComment::id), page.total)
                } catch (error: CancellationException) { throw error
                } catch (error: OfficialForumException.AuthenticationRequired) { throw error
                } catch (_: Exception) { null }
            } }.awaitAll().filterNotNull()
        }
        currentCoroutineContext().ensureActive()
        if (generation != commentGeneration || previews.isEmpty()) return
        val eligible = previews.filterNot { it.first in expandedSubCommentIds }
        mutableState.update { it.copy(subCommentsByRootId = it.subCommentsByRootId + eligible.associate { (id, items) -> id to items },
            subCommentTotals = it.subCommentTotals + eligible.associate { (id, _, total) -> id to total }) }
    }

    private fun commentQuery(page: Int): OfficialForumCommentQuery {
        val state = mutableState.value
        return OfficialForumCommentQuery(postId, page, CommentPageSize, state.commentOrder, state.onlyPostAuthor)
    }

    private companion object {
        const val CommentPageSize = 20
        const val SubCommentPageSize = 20
        const val PreviewSize = 3
        const val MaxSubCommentsLoaded = 200
    }
}

private fun OfficialForumDetailUiState.removingComment(id: Int): OfficialForumDetailUiState {
    val wasTopLevel = comments.any { it.id == id }
    val trimmedComments = if (wasTopLevel) comments.filterNot { it.id == id } else comments
    val trimmedSubComments = subCommentsByRootId.mapValues { (_, items) ->
        items.filterNot { it.id == id }
    }
    val updatedComments = if (wasTopLevel) trimmedComments else trimmedComments.map { comment ->
        val removedFromThisRoot = subCommentsByRootId[comment.id]?.any { it.id == id } == true
        if (removedFromThisRoot) comment.copy(childCount = (comment.childCount - 1).coerceAtLeast(0))
        else comment
    }
    val updatedTotals = if (wasTopLevel) {
        subCommentTotals - id
    } else {
        subCommentTotals.mapValues { (rootId, total) ->
            val removedFromThisRoot = subCommentsByRootId[rootId]?.any { it.id == id } == true
            if (removedFromThisRoot) (total - 1).coerceAtLeast(0) else total
        }
    }
    return copy(
        comments = updatedComments,
        commentTotal = if (wasTopLevel) (commentTotal - 1).coerceAtLeast(0) else commentTotal,
        subCommentsByRootId = if (wasTopLevel) trimmedSubComments - id else trimmedSubComments,
        subCommentTotals = updatedTotals,
        selectedSubCommentRootId =
            if (wasTopLevel && selectedSubCommentRootId == id) null else selectedSubCommentRootId,
        detail = detail?.copy(commentCount = (detail.commentCount - 1).coerceAtLeast(0)),
        deletingCommentIds = deletingCommentIds - id,
    )
}

private fun OfficialForumDetailUiState.applyingCommentLike(
    id: Int,
    value: Int,
): OfficialForumDetailUiState = copy(
    comments = comments.map { if (it.id == id) it.applyingLike(value) else it },
    subCommentsByRootId = subCommentsByRootId.mapValues { (_, items) ->
        items.map { if (it.id == id) it.applyingLike(value) else it }
    },
    likingCommentIds = likingCommentIds - id,
)

private fun OfficialForumComment.applyingLike(value: Int): OfficialForumComment = when {
    value > 0 && !isLiked -> copy(likeCount = likeCount + 1, isLiked = true)
    value < 0 && isLiked -> copy(likeCount = (likeCount - 1).coerceAtLeast(0), isLiked = false)
    else -> this
}

private fun OfficialForumPostDetail.applyingLike(value: Int): OfficialForumPostDetail = when {
    value > 0 -> copy(
        likeCount = likeCount + if (isLiked == true) 0 else 1,
        isLiked = true,
    )
    value < 0 -> copy(
        likeCount = (likeCount - if (isLiked == false) 0 else 1).coerceAtLeast(0),
        isLiked = false,
    )
    else -> this
}

private fun OfficialForumPostDetail.applyingStar(value: Int): OfficialForumPostDetail = when {
    value > 0 -> copy(
        starCount = starCount + if (isStarred == true) 0 else 1,
        isStarred = true,
    )
    value < 0 -> copy(
        starCount = (starCount - if (isStarred == false) 0 else 1).coerceAtLeast(0),
        isStarred = false,
    )
    else -> this
}

private fun String.officialForumCommentHtml(): String = lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString("") { paragraph ->
        "<p>${paragraph.officialForumEmojiHtml()}</p>"
    }

internal fun String.officialForumEmojiHtml(): String {
    val matches = OFFICIAL_FORUM_EMOJI_REGEX.findAll(this).toList()
    if (matches.isEmpty()) return officialForumHtmlEscaped()
    return buildString {
        var position = 0
        matches.forEach { match ->
            append(
                this@officialForumEmojiHtml.substring(position, match.range.first)
                    .officialForumHtmlEscaped(),
            )
            append("<span class=\"at-emo\">")
            append(match.value.officialForumHtmlEscaped())
            append("</span>")
            position = match.range.last + 1
        }
        append(this@officialForumEmojiHtml.substring(position).officialForumHtmlEscaped())
    }
}

internal fun String.officialForumHtmlEscaped(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private val OFFICIAL_FORUM_EMOJI_REGEX = Regex("\\[emo([1-9]|[1-3][0-9]|4[0-6])]")

class OfficialForumListViewModelFactory(private val service: OfficialForumService) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OfficialForumListViewModel(service) as T
}

class OfficialForumDetailViewModelFactory(
    private val service: OfficialForumService,
    private val postId: Int,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OfficialForumDetailViewModel(service, postId) as T
}
