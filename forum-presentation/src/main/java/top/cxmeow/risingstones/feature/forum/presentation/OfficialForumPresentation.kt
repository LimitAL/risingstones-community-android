package top.cxmeow.risingstones.feature.forum.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

class OfficialForumListViewModel(private val service: OfficialForumService) : ViewModel() {
    private val mutableState = MutableStateFlow(OfficialForumListUiState())
    private var refreshJob: Job? = null
    val state: StateFlow<OfficialForumListUiState> = mutableState.asStateFlow()

    fun ensureLoaded() {
        if (mutableState.value.status != OfficialForumLoadStatus.Idle) return
        refreshJob = viewModelScope.launch {
            loadParts()
            refreshNow()
        }
    }

    fun setContentKind(kind: OfficialForumContentKind) {
        if (mutableState.value.contentKind == kind) return
        mutableState.update {
            it.copy(
                contentKind = kind,
                selectedPartIds = emptySet(),
                selectedPostId = null,
                searchText = "",
                loadedSearchText = "",
            )
        }
        refresh()
    }

    fun setSearchText(value: String) {
        val shouldClearCommittedSearch = value.isBlank() &&
            mutableState.value.loadedSearchText.isNotEmpty()
        mutableState.update { it.copy(searchText = value) }
        if (shouldClearCommittedSearch) {
            refresh()
        }
    }

    fun submitSearch() {
        val normalized = mutableState.value.searchText.trim()
        // iOS OfficialForumView.commitSearch() submits every non-empty query,
        // including an explicit re-submit of the currently loaded keywords.
        // Keep the keyboard Search action meaningful instead of treating the
        // same text as a no-op on Android.
        if (normalized.isEmpty()) return
        mutableState.update { it.copy(searchText = normalized) }
        refresh()
    }

    fun applyFilters(partIds: Set<Int>, order: OfficialForumSearchOrder) {
        val current = mutableState.value
        if (current.selectedPartIds == partIds && current.searchOrder == order) return
        mutableState.update {
            it.copy(selectedPartIds = partIds, searchOrder = order)
        }
        refresh()
    }

    fun togglePart(id: Int) {
        mutableState.update { current ->
            current.copy(
                selectedPartIds = current.selectedPartIds.toMutableSet().apply {
                    if (!add(id)) remove(id)
                },
            )
        }
        refresh()
    }

    fun clearParts() {
        if (mutableState.value.selectedPartIds.isEmpty()) return
        mutableState.update { it.copy(selectedPartIds = emptySet()) }
        refresh()
    }

    fun setSearchOrder(order: OfficialForumSearchOrder) {
        if (mutableState.value.searchOrder == order) return
        mutableState.update { it.copy(searchOrder = order) }
        if (mutableState.value.loadedSearchText.isNotEmpty()) refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val snapshot = mutableState.value
        val search = snapshot.searchText.trim()
        mutableState.update {
            it.copy(status = OfficialForumLoadStatus.Loading, loadMoreFailed = false)
        }
        try {
            val page = fetchPage(snapshot, search, 1)
            mutableState.update { current ->
                current.copy(
                    posts = page.items,
                    selectedPostId = current.selectedPostId?.takeIf { selected ->
                        page.items.any { it.id == selected }
                    },
                    loadedSearchText = search,
                    page = page.page,
                    total = page.total,
                    status = OfficialForumLoadStatus.Loaded,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(status = OfficialForumLoadStatus.Failed) }
        }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        if (snapshot.isLoadingMore || snapshot.posts.size >= snapshot.total) return
        mutableState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        viewModelScope.launch {
            try {
                val page = fetchPage(snapshot, snapshot.loadedSearchText, snapshot.page + 1)
                mutableState.update { current ->
                    val merged = (current.posts + page.items).distinctBy(OfficialForumPostSummary::id)
                    current.copy(
                        posts = merged,
                        page = page.page,
                        total = if (page.items.isEmpty() || page.items.size < PageSize) {
                            merged.size
                        } else {
                            maxOf(page.total, merged.size + 1)
                        },
                        isLoadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    fun select(post: OfficialForumPostSummary) {
        selectPostId(post.id)
    }

    fun selectPostId(postId: Int) {
        require(postId > 0) { "postId must be positive" }
        mutableState.update { it.copy(selectedPostId = postId) }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selectedPostId = null) }
    }

    private suspend fun loadParts() {
        if (mutableState.value.parts.isNotEmpty()) return
        runCatching { service.fetchParts() }.getOrNull()?.let { parts ->
            mutableState.update { it.copy(parts = parts) }
        }
    }

    private suspend fun fetchPage(
        state: OfficialForumListUiState,
        search: String,
        page: Int,
    ) = if (search.isNotEmpty()) {
        service.searchPosts(
            OfficialForumSearchQuery(
                contentKind = state.contentKind,
                keywords = search,
                partIds = state.partIdsForQuery(),
                order = state.searchOrder,
                page = page,
                limit = PageSize,
            ),
        )
    } else {
        service.fetchPosts(
            OfficialForumListQuery(
                contentKind = state.contentKind,
                page = page,
                limit = PageSize,
                partIds = state.partIdsForQuery(),
            ),
        )
    }

    private fun OfficialForumListUiState.partIdsForQuery(): List<Int> =
        if (contentKind == OfficialForumContentKind.Guide) emptyList()
        else parts.map(OfficialForumPartFilter::id).filter(selectedPartIds::contains) +
            selectedPartIds.filterNot { selected -> parts.any { it.id == selected } }.sorted()

    private companion object {
        const val PageSize = 20
    }
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
    val isLikingPost: Boolean = false,
    val isStarringPost: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val submittingVoteIds: Set<String> = emptySet(),
    val actionFailed: Boolean = false,
)

data class OfficialForumReplyTarget(
    val parentId: Int,
    val rootParentId: Int,
    val authorName: String? = null,
)

class OfficialForumDetailViewModel(
    private val service: OfficialForumService,
    private val postId: Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfficialForumDetailUiState())
    private var loadJob: Job? = null
    val state: StateFlow<OfficialForumDetailUiState> = mutableState.asStateFlow()

    init { load() }

    fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(status = OfficialForumLoadStatus.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val (detail, comments) = coroutineScope {
                    val detailTask = async { service.fetchPostDetail(postId) }
                    val commentsTask = async { service.fetchComments(commentQuery(1)) }
                    detailTask.await() to commentsTask.await()
                }
                mutableState.update {
                    it.copy(
                        detail = detail,
                        comments = comments.items,
                        commentPage = comments.page,
                        commentTotal = comments.total,
                        status = OfficialForumLoadStatus.Loaded,
                        commentsStatus = OfficialForumLoadStatus.Loaded,
                        subCommentsByRootId = emptyMap(),
                        subCommentTotals = emptyMap(),
                    )
                }
                loadPreviews(comments.items)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(status = OfficialForumLoadStatus.Failed) }
            }
        }
    }

    fun setCommentOrder(order: OfficialForumCommentOrder) {
        if (mutableState.value.commentOrder == order) return
        mutableState.update { it.copy(commentOrder = order) }
        refreshComments()
    }

    fun toggleOnlyPostAuthor() {
        mutableState.update { it.copy(onlyPostAuthor = !it.onlyPostAuthor) }
        refreshComments()
    }

    fun refreshComments(force: Boolean = false) {
        if (!force && mutableState.value.commentsStatus == OfficialForumLoadStatus.Loading) return
        mutableState.update { it.copy(commentsStatus = OfficialForumLoadStatus.Loading) }
        viewModelScope.launch {
            try {
                val page = service.fetchComments(commentQuery(1))
                mutableState.update {
                    it.copy(
                        comments = page.items,
                        commentPage = page.page,
                        commentTotal = page.total,
                        commentsStatus = OfficialForumLoadStatus.Loaded,
                        subCommentsByRootId = emptyMap(),
                        subCommentTotals = emptyMap(),
                    )
                }
                loadPreviews(page.items)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(commentsStatus = OfficialForumLoadStatus.Failed) }
            }
        }
    }

    fun loadMoreComments() {
        val snapshot = mutableState.value
        if (snapshot.isLoadingMoreComments || snapshot.comments.size >= snapshot.commentTotal) return
        mutableState.update { it.copy(isLoadingMoreComments = true, loadMoreFailed = false) }
        viewModelScope.launch {
            try {
                val page = service.fetchComments(commentQuery(snapshot.commentPage + 1))
                val existing = snapshot.comments.map(OfficialForumComment::id).toSet()
                val newComments = page.items.filterNot { it.id in existing }
                mutableState.update { current ->
                    val merged = current.comments + newComments
                    current.copy(
                        comments = merged,
                        commentPage = page.page,
                        commentTotal = if (newComments.isEmpty() || page.items.size < CommentPageSize) {
                            merged.size
                        } else {
                            maxOf(page.total, merged.size + 1)
                        },
                        isLoadingMoreComments = false,
                    )
                }
                loadPreviews(newComments)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(isLoadingMoreComments = false, loadMoreFailed = true)
                }
            }
        }
    }

    fun openSubComments(comment: OfficialForumComment) {
        mutableState.update { it.copy(selectedSubCommentRootId = comment.id) }
        if (mutableState.value.subCommentsByRootId[comment.id].orEmpty().size < comment.childCount) {
            loadAllSubComments(comment)
        }
    }

    fun dismissSubComments() {
        mutableState.update { it.copy(selectedSubCommentRootId = null) }
    }

    fun likePost() {
        val detail = mutableState.value.detail ?: return
        if (mutableState.value.isLikingPost) return
        mutableState.update { it.copy(isLikingPost = true, actionFailed = false) }
        viewModelScope.launch {
            try {
                val value = service.likePost(detail.id)
                mutableState.update { current ->
                    val latest = current.detail ?: return@update current.copy(isLikingPost = false)
                    current.copy(detail = latest.applyingLike(value), isLikingPost = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isLikingPost = false, actionFailed = true) }
            }
        }
    }

    fun starPost() {
        val detail = mutableState.value.detail ?: return
        if (mutableState.value.isStarringPost) return
        mutableState.update { it.copy(isStarringPost = true, actionFailed = false) }
        viewModelScope.launch {
            try {
                val value = service.starPost(detail.id)
                mutableState.update { current ->
                    val latest = current.detail ?: return@update current.copy(isStarringPost = false)
                    current.copy(detail = latest.applyingStar(value), isStarringPost = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isStarringPost = false, actionFailed = true) }
            }
        }
    }

    fun submitComment(
        target: OfficialForumReplyTarget,
        text: String,
        image: OfficialForumCommentImageUpload? = null,
        onSuccess: () -> Unit = {},
    ) {
        val content = text.officialForumCommentHtml()
        if (content.isEmpty() || mutableState.value.isSubmittingComment) return
        mutableState.update { it.copy(isSubmittingComment = true, actionFailed = false) }
        viewModelScope.launch {
            try {
                val commentPictureText = if (image != null) service.uploadCommentImage(image) else ""
                service.submitComment(
                    OfficialForumCommentDraft(
                        postId = postId,
                        parentId = target.parentId,
                        rootParentId = target.rootParentId,
                        contentHtml = content,
                        commentPictureText = commentPictureText,
                    ),
                )
                mutableState.update { current ->
                    current.copy(
                        detail = current.detail?.copy(
                            commentCount = current.detail.commentCount + 1,
                        ),
                        isSubmittingComment = false,
                    )
                }
                refreshComments(force = true)
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isSubmittingComment = false, actionFailed = true) }
            }
        }
    }

    fun submitVote(vote: OfficialForumPostVote, optionIds: Set<Int>) {
        if (vote.id in mutableState.value.submittingVoteIds) return
        val selected = vote.options.filter { it.optionId in optionIds }
        val minimum = maxOf(vote.minimumSelectionCount ?: 1, 1)
        val maximum = vote.maximumSelectionCount
        if (selected.size < minimum || maximum != null && selected.size > maximum) {
            mutableState.update { it.copy(actionFailed = true) }
            return
        }
        mutableState.update {
            it.copy(submittingVoteIds = it.submittingVoteIds + vote.id, actionFailed = false)
        }
        viewModelScope.launch {
            try {
                val result = service.submitVote(
                    OfficialForumVoteDraft(
                        postId = postId,
                        options = selected.map {
                            OfficialForumVoteSelection(it.optionId, it.title)
                        },
                    ),
                )
                mutableState.update { current ->
                    current.copy(
                        detail = current.detail?.copy(
                            votes = current.detail.votes.map { candidate ->
                                if (candidate.id != vote.id) candidate else candidate.copy(
                                    totalUserCount = result.voteTotalUser,
                                    options = candidate.options.map { option ->
                                        option.copy(
                                            totalVoteCount = result.voteDetails[option.optionId]
                                                ?: option.totalVoteCount,
                                            isParticipant = option.optionId in optionIds,
                                        )
                                    },
                                )
                            },
                        ),
                        submittingVoteIds = current.submittingVoteIds - vote.id,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        submittingVoteIds = it.submittingVoteIds - vote.id,
                        actionFailed = true,
                    )
                }
            }
        }
    }

    private fun loadAllSubComments(comment: OfficialForumComment) {
        if (comment.id in mutableState.value.loadingSubCommentIds) return
        mutableState.update { it.copy(loadingSubCommentIds = it.loadingSubCommentIds + comment.id) }
        viewModelScope.launch {
            try {
                val loaded = mutableListOf<OfficialForumComment>()
                var pageNumber = 1
                var total: Int
                do {
                    val page = service.fetchSubComments(
                        OfficialForumSubCommentQuery(comment.id, pageNumber, SubCommentPageSize),
                    )
                    loaded += page.items
                    total = page.total
                    pageNumber += 1
                } while (loaded.size < total && loaded.lastIndex < MaxSubCommentsLoaded)
                mutableState.update {
                    it.copy(
                        subCommentsByRootId = it.subCommentsByRootId +
                            (comment.id to loaded.distinctBy(OfficialForumComment::id)),
                        subCommentTotals = it.subCommentTotals + (comment.id to total),
                        loadingSubCommentIds = it.loadingSubCommentIds - comment.id,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(loadingSubCommentIds = it.loadingSubCommentIds - comment.id)
                }
            }
        }
    }

    private suspend fun loadPreviews(comments: List<OfficialForumComment>) {
        val previews = coroutineScope {
            comments.filter { it.childCount > 0 }.map { comment ->
                async {
                    runCatching {
                        val page = service.fetchSubComments(
                            OfficialForumSubCommentQuery(comment.id, limit = PreviewSize),
                        )
                        Triple(comment.id, page.items, page.total)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (previews.isEmpty()) return
        mutableState.update { current ->
            current.copy(
                subCommentsByRootId = current.subCommentsByRootId +
                    previews.associate { (id, items) -> id to items },
                subCommentTotals = current.subCommentTotals +
                    previews.associate { (id, _, total) -> id to total },
            )
        }
    }

    private fun commentQuery(page: Int): OfficialForumCommentQuery {
        val state = mutableState.value
        return OfficialForumCommentQuery(
            postId = postId,
            page = page,
            limit = CommentPageSize,
            order = state.commentOrder,
            onlyPostAuthor = state.onlyPostAuthor,
        )
    }

    private companion object {
        const val CommentPageSize = 20
        const val SubCommentPageSize = 20
        const val PreviewSize = 3
        const val MaxSubCommentsLoaded = 199
    }
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

private fun String.officialForumEmojiHtml(): String {
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

private fun String.officialForumHtmlEscaped(): String = replace("&", "&amp;")
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
