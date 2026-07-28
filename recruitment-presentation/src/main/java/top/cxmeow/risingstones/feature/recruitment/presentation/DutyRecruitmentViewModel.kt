package top.cxmeow.risingstones.feature.recruitment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentCatalogs
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentPosition
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentService
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentMember
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentRating
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReview
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentSubcomment

enum class RecruitmentBoardKind { Duty, Beginner, Guild, Other, RolePlay }

val RecruitmentBoardKind.communityKind: CommunityRecruitmentKind?
    get() = when (this) {
        RecruitmentBoardKind.Duty -> null
        RecruitmentBoardKind.Beginner -> CommunityRecruitmentKind.Beginner
        RecruitmentBoardKind.Guild -> CommunityRecruitmentKind.Guild
        RecruitmentBoardKind.Other -> CommunityRecruitmentKind.Other
        RecruitmentBoardKind.RolePlay -> CommunityRecruitmentKind.RolePlay
    }

data class DutyRecruitmentUiState(
    val board: RecruitmentBoardKind = RecruitmentBoardKind.Duty,
    val dutyQuery: DutyRecruitmentListQuery = DutyRecruitmentListQuery(),
    val communityQuery: CommunityRecruitmentQuery = CommunityRecruitmentQuery(CommunityRecruitmentKind.Beginner),
    val dutyItems: List<DutyRecruitmentSummary> = emptyList(),
    val communityItems: List<CommunityRecruitmentSummary> = emptyList(),
    val page: Int = 1,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val catalogs: DutyRecruitmentCatalogs = DutyRecruitmentCatalogs(),
    val isLoadingCatalogs: Boolean = false,
    val catalogsError: String? = null,
    val filterCatalog: CommunityRecruitmentFilterCatalog = CommunityRecruitmentFilterCatalog(),
    val isLoadingFilterCatalog: Boolean = false,
    val filterCatalogError: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val listError: String? = null,
    val listErrorIsPagination: Boolean = false,
    val selectedId: Int? = null,
    val dutyDetail: DutyRecruitmentDetail? = null,
    val communityDetail: CommunityRecruitmentDetail? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,
    val members: List<RolePlayRecruitmentMember> = emptyList(),
    val isLoadingMembers: Boolean = false,
    val membersError: String? = null,
    val reviews: List<RolePlayRecruitmentReview> = emptyList(),
    val reviewsPage: Int = 0,
    val hasMoreReviews: Boolean = false,
    val isLoadingReviews: Boolean = false,
    val isLoadingMoreReviews: Boolean = false,
    val reviewsError: String? = null,
    val rating: RolePlayRecruitmentRating? = null,
    val isLoadingRating: Boolean = false,
    val ratingError: String? = null,
    val subcomments: Map<String, List<RolePlayRecruitmentSubcomment>> = emptyMap(),
    val loadingSubcommentIds: Set<String> = emptySet(),
    val likingReviewIds: Set<String> = emptySet(),
    val isResponding: Boolean = false,
    val responseContactInfo: String? = null,
    val responseError: String? = null,
    val interactionError: String? = null,
    // Kept for source compatibility with the first functional implementation.
    val error: String? = null,
    val notice: RecruitmentNotice? = null,
)

enum class RecruitmentNotice { ResponseSubmitted }

class DutyRecruitmentViewModel(
    private val service: DutyRecruitmentService,
    private val autoLoadList: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DutyRecruitmentUiState())
    val state: StateFlow<DutyRecruitmentUiState> = mutableState.asStateFlow()
    val hasCommunityIdentity: Boolean get() = service.hasCommunityIdentity
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private val communityQueries = CommunityRecruitmentKind.entries.associateWithTo(mutableMapOf()) {
        CommunityRecruitmentQuery(it)
    }

    init {
        loadCatalogs()
        if (autoLoadList) refresh()
    }

    fun selectBoard(board: RecruitmentBoardKind, loadList: Boolean = true) {
        if (mutableState.value.board == board) {
            if (loadList && currentItemsAreEmpty()) refresh()
            return
        }
        listJob?.cancel()
        detailJob?.cancel()
        val communityKind = board.communityKind
        mutableState.update {
            it.copy(
                board = board,
                communityQuery = communityKind?.let { kind -> communityQueries.getValue(kind) }
                    ?: it.communityQuery,
                dutyItems = emptyList(),
                communityItems = emptyList(),
                page = 1,
                totalCount = 0,
                hasMore = false,
                selectedId = null,
                dutyDetail = null,
                communityDetail = null,
                members = emptyList(),
                reviews = emptyList(),
                reviewsPage = 0,
                hasMoreReviews = false,
                rating = null,
                subcomments = emptyMap(),
                filterCatalog = CommunityRecruitmentFilterCatalog(),
                isLoadingFilterCatalog = false,
                filterCatalogError = null,
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                listError = null,
                listErrorIsPagination = false,
                isLoadingDetail = false,
                detailError = null,
                isLoadingMembers = false,
                membersError = null,
                isLoadingReviews = false,
                isLoadingMoreReviews = false,
                reviewsError = null,
                isLoadingRating = false,
                ratingError = null,
                responseContactInfo = null,
                responseError = null,
                interactionError = null,
                error = null,
                notice = null,
            )
        }
        if (communityKind != null && (!communityKind.requiresAuthentication || hasCommunityIdentity)) {
            loadFilterCatalog(communityKind)
        }
        if (loadList && (board != RecruitmentBoardKind.Guild || hasCommunityIdentity)) refresh()
    }

    fun applyDutyFilter(dutyType: String, dutyName: String, position: DutyRecruitmentPosition?) {
        mutableState.update {
            it.copy(
                dutyQuery = it.dutyQuery.copy(
                    dutyType = dutyType,
                    dutyName = dutyName,
                    position = position,
                ),
            )
        }
        refresh()
    }

    fun applyCommunityFilter(query: CommunityRecruitmentQuery) {
        val currentKind = mutableState.value.board.communityKind ?: return
        if (query.kind != currentKind) return
        val normalized = query.copy(page = 1, limit = CommunityPageSize)
        communityQueries[currentKind] = normalized
        mutableState.update { it.copy(communityQuery = normalized) }
        refresh()
    }

    fun refresh() {
        val requested = mutableState.value
        if (requested.board == RecruitmentBoardKind.Guild && !hasCommunityIdentity) return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val hasContent = if (requested.board == RecruitmentBoardKind.Duty) {
                requested.dutyItems.isNotEmpty()
            } else {
                requested.communityItems.isNotEmpty()
            }
            mutableState.update {
                it.copy(
                    isLoading = !hasContent,
                    isRefreshing = hasContent,
                    isLoadingMore = false,
                    listError = null,
                    listErrorIsPagination = false,
                    page = 1,
                )
            }
            try {
                if (requested.board == RecruitmentBoardKind.Duty) {
                    val result = service.fetchDutyRecruitments(
                        requested.dutyQuery.copy(page = 1, limit = DutyPageSize),
                    )
                    mutableState.update { current ->
                        if (current.board != requested.board || current.dutyQuery != requested.dutyQuery) current
                        else current.copy(
                            dutyItems = result.items,
                            communityItems = emptyList(),
                            page = result.currentPage,
                            totalCount = result.totalCount,
                            hasMore = result.items.size < result.totalCount,
                            isLoading = false,
                            isRefreshing = false,
                            listError = null,
                            listErrorIsPagination = false,
                        )
                    }
                } else {
                    val result = service.fetchCommunityRecruitments(
                        requested.communityQuery.copy(page = 1, limit = CommunityPageSize),
                    )
                    mutableState.update { current ->
                        if (current.board != requested.board || current.communityQuery != requested.communityQuery) current
                        else current.copy(
                            communityItems = result.items,
                            dutyItems = emptyList(),
                            page = result.currentPage,
                            totalCount = result.totalCount,
                            hasMore = result.items.size >= CommunityPageSize,
                            isLoading = false,
                            isRefreshing = false,
                            listError = null,
                            listErrorIsPagination = false,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { current ->
                    if (current.board != requested.board) current
                    else current.copy(
                        hasMore = if (hasContent) current.hasMore else false,
                        isLoading = false,
                        isRefreshing = false,
                        listError = error.message,
                        listErrorIsPagination = false,
                    )
                }
            }
        }
    }

    fun loadMore() {
        val requested = mutableState.value
        if (!requested.hasMore || requested.isLoading || requested.isLoadingMore) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(isLoadingMore = true, listError = null, listErrorIsPagination = false)
            }
            try {
                val nextPage = requested.page + 1
                if (requested.board == RecruitmentBoardKind.Duty) {
                    val result = service.fetchDutyRecruitments(
                        requested.dutyQuery.copy(page = nextPage, limit = DutyPageSize),
                    )
                    mutableState.update { current ->
                        if (current.board != requested.board || current.dutyQuery != requested.dutyQuery) current
                        else {
                            val ids = current.dutyItems.mapTo(mutableSetOf(), DutyRecruitmentSummary::id)
                            val next = result.items.filterNot { it.id in ids }
                            current.copy(
                                dutyItems = current.dutyItems + next,
                                page = result.currentPage,
                                totalCount = result.totalCount,
                                hasMore = current.dutyItems.size + next.size < result.totalCount && next.isNotEmpty(),
                                isLoadingMore = false,
                            )
                        }
                    }
                } else {
                    val result = service.fetchCommunityRecruitments(
                        requested.communityQuery.copy(page = nextPage, limit = CommunityPageSize),
                    )
                    mutableState.update { current ->
                        if (current.board != requested.board || current.communityQuery != requested.communityQuery) current
                        else {
                            val ids = current.communityItems.mapTo(mutableSetOf(), CommunityRecruitmentSummary::id)
                            val next = result.items.filterNot { it.id in ids }
                            current.copy(
                                communityItems = current.communityItems + next,
                                page = result.currentPage,
                                totalCount = result.totalCount,
                                hasMore = result.items.size >= CommunityPageSize && next.isNotEmpty(),
                                isLoadingMore = false,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { current ->
                    if (current.board == requested.board) current.copy(
                        isLoadingMore = false,
                        listError = error.message,
                        listErrorIsPagination = true,
                    ) else current
                }
            }
        }
    }

    fun selectDetail(id: Int) {
        val current = mutableState.value
        val hasCurrentDetail = current.dutyDetail?.summary?.id == id ||
            current.communityDetail?.summary?.id == id
        if (current.selectedId == id && hasCurrentDetail) return
        mutableState.update {
            it.copy(
                selectedId = id,
                dutyDetail = null,
                communityDetail = null,
                isLoadingDetail = false,
                detailError = null,
                members = emptyList(),
                isLoadingMembers = false,
                membersError = null,
                reviews = emptyList(),
                reviewsPage = 0,
                hasMoreReviews = false,
                isLoadingReviews = false,
                reviewsError = null,
                rating = null,
                isLoadingRating = false,
                ratingError = null,
                subcomments = emptyMap(),
                responseContactInfo = null,
                responseError = null,
                interactionError = null,
            )
        }
        loadDetail(id, preserveContent = false)
    }

    fun clearSelection() {
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedId = null,
                dutyDetail = null,
                communityDetail = null,
                isLoadingDetail = false,
                detailError = null,
                members = emptyList(),
                isLoadingMembers = false,
                membersError = null,
                reviews = emptyList(),
                reviewsPage = 0,
                hasMoreReviews = false,
                isLoadingReviews = false,
                isLoadingMoreReviews = false,
                reviewsError = null,
                rating = null,
                isLoadingRating = false,
                ratingError = null,
                subcomments = emptyMap(),
                loadingSubcommentIds = emptySet(),
                likingReviewIds = emptySet(),
                responseContactInfo = null,
                responseError = null,
                interactionError = null,
            )
        }
    }

    fun refreshDetail() {
        mutableState.value.selectedId?.let { loadDetail(it, preserveContent = true) }
    }

    fun retryDetail() {
        mutableState.value.selectedId?.let { loadDetail(it, preserveContent = false) }
    }

    private fun loadDetail(id: Int, preserveContent: Boolean) {
        detailJob?.cancel()
        val requestedBoard = mutableState.value.board
        detailJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isLoadingDetail = true,
                    dutyDetail = if (preserveContent) it.dutyDetail else null,
                    communityDetail = if (preserveContent) it.communityDetail else null,
                    detailError = null,
                )
            }
            try {
                if (requestedBoard == RecruitmentBoardKind.Duty) {
                    val detail = service.fetchDutyRecruitmentDetail(id)
                    mutableState.update { current ->
                        if (current.board == requestedBoard && current.selectedId == id) current.copy(
                            dutyDetail = detail,
                            communityDetail = null,
                            isLoadingDetail = false,
                            detailError = null,
                        ) else current
                    }
                } else {
                    val kind = requireNotNull(requestedBoard.communityKind)
                    val detail = service.fetchCommunityRecruitmentDetail(id, kind)
                    mutableState.update { current ->
                        if (current.board == requestedBoard && current.selectedId == id) current.copy(
                            communityDetail = detail,
                            dutyDetail = null,
                            isLoadingDetail = false,
                            detailError = null,
                        ) else current
                    }
                    if (kind == CommunityRecruitmentKind.RolePlay) loadRolePlayExtras(id, requestedBoard)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { current ->
                    if (current.board == requestedBoard && current.selectedId == id) current.copy(
                        isLoadingDetail = false,
                        detailError = error.message,
                    ) else current
                }
            }
        }
    }

    fun respond(contactInfo: String, onSuccess: () -> Unit = {}) {
        val requested = mutableState.value
        val id = requested.selectedId ?: return
        val normalized = contactInfo.trim().take(MaximumContactLength)
        if (normalized.isEmpty() || requested.isResponding) return
        viewModelScope.launch {
            mutableState.update { it.copy(isResponding = true, responseError = null) }
            try {
                val returned = if (requested.board == RecruitmentBoardKind.Beginner) {
                    service.respondToBeginnerRecruitment(id, normalized)
                } else {
                    service.respondToDutyRecruitment(id, normalized)
                }
                mutableState.update { current ->
                    if (current.board != requested.board || current.selectedId != id) current
                    else current.copy(
                        isResponding = false,
                        responseContactInfo = returned ?: normalized,
                        dutyDetail = current.dutyDetail?.copy(isResponded = true),
                        communityDetail = current.communityDetail?.let { detail ->
                            detail.copy(
                                summary = detail.summary.copy(
                                    beginner = detail.summary.beginner?.copy(isResponded = true),
                                ),
                            )
                        },
                        responseError = null,
                        notice = RecruitmentNotice.ResponseSubmitted,
                    )
                }
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(isResponding = false, responseError = error.message) }
            }
        }
    }

    fun likeReview(review: RolePlayRecruitmentReview) {
        if (review.id in mutableState.value.likingReviewIds) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    likingReviewIds = it.likingReviewIds + review.id,
                    interactionError = null,
                    error = null,
                )
            }
            try {
                val result = service.likeRolePlayReview(review.id)
                mutableState.update { state ->
                    state.copy(
                        reviews = state.reviews.map { item ->
                            if (item.id != review.id) item else {
                                val nextLiked = result == 1
                                val delta = if (nextLiked == item.isLiked) 0 else if (nextLiked) 1 else -1
                                item.copy(
                                    isLiked = nextLiked,
                                    likeCount = (item.likeCount + delta).coerceAtLeast(0),
                                )
                            }
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(interactionError = error.message, error = error.message) }
            } finally {
                mutableState.update { it.copy(likingReviewIds = it.likingReviewIds - review.id) }
            }
        }
    }

    fun loadSubcomments(review: RolePlayRecruitmentReview) {
        val current = mutableState.value
        if (current.subcomments[review.id].orEmpty().size >= review.childCount ||
            review.id in current.loadingSubcommentIds
        ) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loadingSubcommentIds = it.loadingSubcommentIds + review.id,
                    interactionError = null,
                )
            }
            try {
                val page = service.fetchRolePlaySubcomments(review.id, 1, maxOf(review.childCount, 10))
                mutableState.update {
                    it.copy(subcomments = it.subcomments + (review.id to page.items))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(interactionError = error.message, error = error.message) }
            } finally {
                mutableState.update {
                    it.copy(loadingSubcommentIds = it.loadingSubcommentIds - review.id)
                }
            }
        }
    }

    fun loadMoreReviews() {
        val current = mutableState.value
        val id = current.selectedId ?: return
        if (!current.hasMoreReviews || current.isLoadingMoreReviews) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingMoreReviews = true, reviewsError = null) }
            try {
                val page = service.fetchRolePlayReviews(id, current.reviewsPage + 1, ReviewPageSize)
                val nextWithPreviews = attachSubcommentPreviews(page.items)
                mutableState.update { state ->
                    if (state.selectedId != id) state else {
                        val ids = state.reviews.mapTo(mutableSetOf(), RolePlayRecruitmentReview::id)
                        val next = nextWithPreviews.filterNot { it.id in ids }
                        state.copy(
                            reviews = state.reviews + next,
                            reviewsPage = page.page,
                            hasMoreReviews = page.hasMore && next.isNotEmpty(),
                            isLoadingMoreReviews = false,
                            reviewsError = null,
                            subcomments = state.subcomments + next.mapNotNull { review ->
                                review.childPreviewReplies.takeIf(List<RolePlayRecruitmentSubcomment>::isNotEmpty)
                                    ?.let { review.id to it }
                            }.toMap(),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(isLoadingMoreReviews = false, reviewsError = error.message)
                }
            }
        }
    }

    fun loadCatalogs() {
        if (mutableState.value.isLoadingCatalogs) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingCatalogs = true, catalogsError = null) }
            try {
                val catalogs = service.fetchCatalogs()
                mutableState.update {
                    it.copy(catalogs = catalogs, isLoadingCatalogs = false, catalogsError = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { it.copy(isLoadingCatalogs = false, catalogsError = error.message) }
            }
        }
    }

    fun retryFilterCatalog() {
        mutableState.value.board.communityKind?.let(::loadFilterCatalog)
    }

    fun clearResponseError() {
        mutableState.update { it.copy(responseError = null) }
    }

    fun clearNotice() {
        mutableState.update {
            it.copy(
                error = null,
                notice = null,
                interactionError = null,
            )
        }
    }

    private fun loadFilterCatalog(kind: CommunityRecruitmentKind) {
        viewModelScope.launch {
            mutableState.update {
                if (it.board.communityKind == kind) it.copy(
                    isLoadingFilterCatalog = true,
                    filterCatalogError = null,
                ) else it
            }
            try {
                val catalog = service.fetchCommunityFilterCatalog(kind)
                mutableState.update {
                    if (it.board.communityKind == kind) it.copy(
                        filterCatalog = catalog,
                        isLoadingFilterCatalog = false,
                        filterCatalogError = null,
                    ) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.board.communityKind == kind) it.copy(
                        isLoadingFilterCatalog = false,
                        filterCatalogError = error.message,
                    ) else it
                }
            }
        }
    }

    private suspend fun loadRolePlayExtras(id: Int, board: RecruitmentBoardKind) = supervisorScope {
        mutableState.update {
            if (it.board == board && it.selectedId == id) it.copy(
                isLoadingMembers = true,
                membersError = null,
                isLoadingReviews = true,
                reviewsError = null,
                isLoadingRating = true,
                ratingError = null,
            ) else it
        }

        launch {
            try {
                val members = service.fetchRolePlayMembers(id)
                mutableState.update {
                    if (it.board == board && it.selectedId == id) it.copy(
                        members = members,
                        isLoadingMembers = false,
                    ) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.board == board && it.selectedId == id) it.copy(
                        isLoadingMembers = false,
                        membersError = error.message,
                    ) else it
                }
            }
        }
        launch {
            try {
                val page = service.fetchRolePlayReviews(id, 1, ReviewPageSize)
                val reviews = attachSubcommentPreviews(page.items)
                mutableState.update {
                    if (it.board == board && it.selectedId == id) it.copy(
                        reviews = reviews,
                        reviewsPage = page.page,
                        hasMoreReviews = page.hasMore,
                        isLoadingReviews = false,
                        subcomments = reviews.mapNotNull { review ->
                            review.childPreviewReplies.takeIf(List<RolePlayRecruitmentSubcomment>::isNotEmpty)
                                ?.let { review.id to it }
                        }.toMap(),
                    ) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.board == board && it.selectedId == id) it.copy(
                        isLoadingReviews = false,
                        reviewsError = error.message,
                    ) else it
                }
            }
        }
        launch {
            try {
                val rating = service.fetchRolePlayRating(id)
                mutableState.update {
                    if (it.board == board && it.selectedId == id) it.copy(
                        rating = rating,
                        isLoadingRating = false,
                    ) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    if (it.board == board && it.selectedId == id) it.copy(
                        isLoadingRating = false,
                        ratingError = error.message,
                    ) else it
                }
            }
        }
    }

    private suspend fun attachSubcommentPreviews(
        reviews: List<RolePlayRecruitmentReview>,
    ): List<RolePlayRecruitmentReview> = supervisorScope {
        val previews = reviews.filter { it.childCount > 0 }.map { review ->
            async {
                val replies = runCatching {
                    service.fetchRolePlaySubcomments(review.id, 1, PreviewReplyCount).items
                }.getOrDefault(emptyList())
                review.id to replies
            }
        }.awaitAll().toMap()
        reviews.map { review -> review.copy(childPreviewReplies = previews[review.id].orEmpty()) }
    }

    private fun currentItemsAreEmpty(): Boolean = mutableState.value.let {
        if (it.board == RecruitmentBoardKind.Duty) it.dutyItems.isEmpty() else it.communityItems.isEmpty()
    }

    companion object {
        const val MaximumContactLength = 30
        private const val DutyPageSize = 20
        private const val CommunityPageSize = 20
        private const val ReviewPageSize = 10
        private const val PreviewReplyCount = 3
    }
}

class DutyRecruitmentViewModelFactory(
    private val service: DutyRecruitmentService,
    private val autoLoadList: Boolean = true,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DutyRecruitmentViewModel(service, autoLoadList) as T
}
