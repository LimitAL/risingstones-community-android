package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.dynamic.domain.*

enum class DynamicLoadStatus { Idle, Loading, Loaded, Failed, AuthenticationRequired, Unavailable }

data class DynamicUiState(
    val items: List<DynamicEntry> = emptyList(),
    val status: DynamicLoadStatus = DynamicLoadStatus.Idle,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val selectedId: Int? = null,
    val canNavigateBackInDetail: Boolean = false,
    val detail: DynamicEntry? = null,
    val detailStatus: DynamicLoadStatus = DynamicLoadStatus.Idle,
    val comments: List<DynamicComment> = emptyList(),
    val commentsStatus: DynamicLoadStatus = DynamicLoadStatus.Idle,
    val commentsPage: Int = 1,
    val commentsHaveMore: Boolean = false,
    val loadingMoreComments: Boolean = false,
    val replies: Map<Int, List<DynamicComment>> = emptyMap(),
    val loadingReplies: Set<Int> = emptySet(),
    val failedReplies: Set<Int> = emptySet(),
    val repliesHaveMore: Set<Int> = emptySet(),
)

class DynamicViewModel(private val service: DynamicService) : ViewModel() {
    private val mutableState = MutableStateFlow(DynamicUiState())
    val state = mutableState.asStateFlow()
    private var feedJob: Job? = null
    private var moreJob: Job? = null
    private var detailJob: Job? = null
    private var commentsJob: Job? = null
    private val replyJobs = mutableMapOf<Int, Job>()
    private var feedGeneration = 0L
    private var detailGeneration = 0L
    private var feedCursor: String? = null
    private var commentCursor: String? = null
    private var failedCommentPage: Int? = null
    private val replyPages = mutableMapOf<Int, Int>()
    private val replyCursors = mutableMapOf<Int, String?>()
    private val detailHistory = mutableListOf<Int>()

    fun ensureLoaded() {
        if (state.value.status == DynamicLoadStatus.Idle) refresh()
    }

    fun clearProtectedContent() {
        feedGeneration++
        detailGeneration++
        feedJob?.cancel()
        moreJob?.cancel()
        cancelDetails()
        feedCursor = null
        detailHistory.clear()
        mutableState.value = DynamicUiState()
    }

    fun refresh() {
        if (!service.canRead) { deny(DynamicLoadStatus.Unavailable); return }
        val generation = ++feedGeneration
        feedJob?.cancel()
        moreJob?.cancel()
        mutableState.update { it.copy(status = DynamicLoadStatus.Loading, loadingMore = false, loadMoreFailed = false) }
        feedJob = viewModelScope.launch {
            try {
                val page = service.fetchFeed(DynamicListQuery())
                if (generation != feedGeneration) return@launch
                feedCursor = page.pageTime
                mutableState.update { it.copy(items = page.items.distinctBy(DynamicEntry::id),
                    status = DynamicLoadStatus.Loaded, page = page.page, hasMore = page.hasMore) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == feedGeneration && !handleAccessError(error)) {
                    mutableState.update { it.copy(status = DynamicLoadStatus.Failed) }
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = state.value
        if (!snapshot.hasMore || snapshot.loadingMore || snapshot.status != DynamicLoadStatus.Loaded) return
        val generation = feedGeneration
        mutableState.update { it.copy(loadingMore = true, loadMoreFailed = false) }
        moreJob = viewModelScope.launch {
            try {
                val page = service.fetchFeed(DynamicListQuery(snapshot.page + 1, pageTime = feedCursor))
                if (generation != feedGeneration) return@launch
                feedCursor = page.pageTime ?: feedCursor
                mutableState.update { current ->
                    val merged = (current.items + page.items).distinctBy(DynamicEntry::id)
                    current.copy(items = merged, page = page.page,
                        hasMore = page.hasMore && merged.size > current.items.size, loadingMore = false)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == feedGeneration && !handleAccessError(error)) {
                    mutableState.update { it.copy(loadingMore = false, loadMoreFailed = true) }
                }
            }
        }
    }

    fun select(id: Int) {
        detailHistory.clear()
        selectDetail(id)
    }

    fun openLinkedDynamic(id: Int) {
        require(id > 0)
        if (id == state.value.selectedId) return
        state.value.selectedId?.let(detailHistory::add)
        selectDetail(id)
    }

    private fun selectDetail(id: Int) {
        require(id > 0)
        mutableState.update { it.copy(canNavigateBackInDetail = detailHistory.isNotEmpty()) }
        if (state.value.selectedId == id) return
        mutableState.update { it.copy(selectedId = id, detail = null, detailStatus = DynamicLoadStatus.Idle,
            comments = emptyList(), replies = emptyMap()) }
        refreshDetail()
    }

    fun clearSelection() {
        if (detailHistory.isNotEmpty()) {
            selectDetail(detailHistory.removeAt(detailHistory.lastIndex))
            return
        }
        detailGeneration++
        cancelDetails()
        mutableState.update { it.copy(selectedId = null, detail = null, comments = emptyList(), replies = emptyMap()) }
    }

    fun refreshDetail() {
        val id = state.value.selectedId ?: return
        val generation = ++detailGeneration
        cancelDetails()
        mutableState.update { it.copy(detailStatus = DynamicLoadStatus.Loading,
            commentsStatus = DynamicLoadStatus.Loading,
            commentsPage = 1, commentsHaveMore = false, loadingMoreComments = false,
            loadingReplies = emptySet(), failedReplies = emptySet(), repliesHaveMore = emptySet()) }
        detailJob = viewModelScope.launch {
            coroutineScope {
                launch { loadComments(id, 1, generation) }
                try {
                    val detail = service.fetchDetail(id)
                    if (generation == detailGeneration) mutableState.update {
                        it.copy(detail = detail, detailStatus = DynamicLoadStatus.Loaded)
                    }
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    if (generation == detailGeneration && !handleAccessError(error)) {
                        mutableState.update { it.copy(detailStatus = DynamicLoadStatus.Failed) }
                    }
                }
            }
        }
    }

    fun loadMoreComments() {
        val snapshot = state.value
        val id = snapshot.selectedId ?: return
        if (snapshot.loadingMoreComments || snapshot.commentsStatus == DynamicLoadStatus.Loading) return
        val retryPage = failedCommentPage
        if (retryPage == null && !snapshot.commentsHaveMore) return
        val generation = detailGeneration
        mutableState.update { it.copy(loadingMoreComments = true) }
        commentsJob = viewModelScope.launch { loadComments(id, retryPage ?: (snapshot.commentsPage + 1), generation) }
    }

    private suspend fun loadComments(id: Int, pageNumber: Int, generation: Long) {
        try {
            val page = service.fetchComments(id, DynamicListQuery(pageNumber, pageTime = commentCursor))
            if (generation != detailGeneration) return
            commentCursor = page.pageTime ?: commentCursor
            failedCommentPage = null
            mutableState.update {
                it.copy(comments = ((if (pageNumber == 1) emptyList() else it.comments) + page.items)
                    .distinctBy(DynamicComment::id), commentsPage = page.page,
                    commentsHaveMore = page.hasMore, commentsStatus = DynamicLoadStatus.Loaded,
                    loadingMoreComments = false,
                    replies = if (pageNumber == 1) emptyMap() else it.replies)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            if (generation == detailGeneration && !handleAccessError(error)) {
                failedCommentPage = pageNumber
                mutableState.update { it.copy(commentsStatus = DynamicLoadStatus.Failed, loadingMoreComments = false) }
            }
        }
    }

    fun loadReplies(rootId: Int) {
        if (rootId in state.value.loadingReplies || state.value.selectedId == null) return
        if (rootId in replyPages && rootId !in state.value.repliesHaveMore) return
        val generation = detailGeneration
        mutableState.update { it.copy(loadingReplies = it.loadingReplies + rootId, failedReplies = it.failedReplies - rootId) }
        replyJobs[rootId] = viewModelScope.launch {
            try {
                val page = service.fetchReplies(rootId, DynamicListQuery((replyPages[rootId] ?: 0) + 1,
                    pageTime = replyCursors[rootId]))
                if (generation != detailGeneration) return@launch
                replyPages[rootId] = page.page
                replyCursors[rootId] = page.pageTime
                mutableState.update { it.copy(replies = it.replies + (rootId to
                    (it.replies[rootId].orEmpty() + page.items).distinctBy(DynamicComment::id)),
                    loadingReplies = it.loadingReplies - rootId,
                    repliesHaveMore = if (page.hasMore) it.repliesHaveMore + rootId else it.repliesHaveMore - rootId) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == detailGeneration && !handleAccessError(error)) {
                    mutableState.update { it.copy(loadingReplies = it.loadingReplies - rootId, failedReplies = it.failedReplies + rootId) }
                }
            }
        }
    }

    fun applyLikeResult(result: DynamicLikeResult) {
        val id = state.value.selectedId ?: return
        fun DynamicEntry.changed(): DynamicEntry {
            val liked = result == DynamicLikeResult.Liked
            if (isLiked == liked) return this
            return copy(isLiked = liked, likeCount = (likeCount + if (liked) 1 else -1).coerceAtLeast(0))
        }
        mutableState.update { current -> current.copy(
            detail = current.detail?.takeIf { it.id == id }?.changed() ?: current.detail,
            items = current.items.map { if (it.id == id) it.changed() else it },
        ) }
    }

    fun removeDeletedSelected() {
        val id = state.value.selectedId ?: return
        clearSelection()
        mutableState.update { current -> current.copy(items = current.items.filterNot { it.id == id }) }
    }

    private fun cancelDetails() {
        detailJob?.cancel()
        commentsJob?.cancel()
        replyJobs.values.forEach(Job::cancel)
        replyJobs.clear()
        replyPages.clear()
        replyCursors.clear()
        commentCursor = null
        failedCommentPage = null
    }

    private fun handleAccessError(error: Exception): Boolean = when (error) {
        DynamicException.AuthenticationRequired -> { deny(DynamicLoadStatus.AuthenticationRequired); true }
        DynamicException.Unavailable -> { deny(DynamicLoadStatus.Unavailable); true }
        else -> false
    }

    private fun deny(status: DynamicLoadStatus) {
        clearProtectedContent()
        mutableState.value = DynamicUiState(status = status)
    }
}
