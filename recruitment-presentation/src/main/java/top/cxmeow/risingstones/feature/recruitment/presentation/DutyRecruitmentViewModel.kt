package top.cxmeow.risingstones.feature.recruitment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentAuthorService
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentActionEligibilityService
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentAuthorPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayReviewAuthorPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlaySubcommentAuthorPage
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentCatalogs
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentException
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentInteractionService
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentBrowsingService
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentResponseEligibilityService
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentBrowseQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewOrder
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewQuery
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

enum class RecruitmentInteractionError { Unavailable, AuthenticationRequired, InvalidInput, Failed }

/** Transient interaction state retained by the ViewModel, never persisted to storage. */
data class RecruitmentInteractionState(
    val isCurrentUserAuthor: Boolean? = null,
    val isResponseComposerOpen: Boolean = false,
    val contactDraft: String = "",
    val responseSuccessRevision: Long = 0,
    val hasRefreshedResponseContact: Boolean = false,
    val responseError: RecruitmentInteractionError? = null,
    val interactionError: RecruitmentInteractionError? = null,
    val selectedReviewId: String? = null,
    val reviewsErrorIsPagination: Boolean = false,
    val subcommentPages: Map<String, Int> = emptyMap(),
    val hasMoreSubcommentIds: Set<String> = emptySet(),
    val subcommentErrors: Map<String, RecruitmentInteractionError> = emptyMap(),
    val subcommentErrorIsPaginationIds: Set<String> = emptySet(),
)

data class RecruitmentBrowsingState(
    val dutyQuery: DutyRecruitmentBrowseQuery = DutyRecruitmentBrowseQuery(),
    val dutyFilterCatalog: DutyRecruitmentFilterCatalog? = null,
    val reviewOrder: RolePlayRecruitmentReviewOrder = RolePlayRecruitmentReviewOrder.Latest,
)

class DutyRecruitmentViewModel(
    private val service: DutyRecruitmentService,
    private val autoLoadList: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DutyRecruitmentUiState())
    val state: StateFlow<DutyRecruitmentUiState> = mutableState.asStateFlow()
    private val mutableAuthorState = MutableStateFlow(RecruitmentAuthorState())
    val authorState: StateFlow<RecruitmentAuthorState> = mutableAuthorState.asStateFlow()
    private val mutableInteractionState = MutableStateFlow(RecruitmentInteractionState())
    val interactionState: StateFlow<RecruitmentInteractionState> = mutableInteractionState.asStateFlow()
    private val mutableBrowsingState = MutableStateFlow(RecruitmentBrowsingState())
    val browsingState: StateFlow<RecruitmentBrowsingState> = mutableBrowsingState.asStateFlow()
    val canBrowseExtended: Boolean get() = !cleared && service is RecruitmentBrowsingService
    val canRespond: Boolean get() = canInteract && validResponseTarget()
    private var cleared = false
    private var authorizationRevoked = false
    val hasCommunityIdentity: Boolean get() = !cleared && !authorizationRevoked && service.hasCommunityIdentity
    val canWrite: Boolean get() = !cleared && !authorizationRevoked &&
        (service as? RecruitmentInteractionService)?.canPerformAuthenticatedWrites == true
    val canAttemptWrite: Boolean get() = !cleared && !authorizationRevoked &&
        (service as? RecruitmentActionEligibilityService)?.canAttemptAuthenticatedWrites == true
    val canInteract: Boolean get() = canWrite || canAttemptWrite

    private var nextRequest = 0L
    private val requests = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
    private val communityQueries = CommunityRecruitmentKind.entries.associateWithTo(mutableMapOf()) {
        CommunityRecruitmentQuery(it)
    }
    private val respondedTargets = mutableMapOf<Pair<RecruitmentBoardKind, Int>, String?>()
    private val confirmedLikes = mutableMapOf<String, Pair<Boolean, Int>>()

    init {
        loadCatalogs()
        if (autoLoadList) refresh()
    }

    fun selectBoard(board: RecruitmentBoardKind, loadList: Boolean = true) {
        if (cleared) return
        if (mutableState.value.board == board) {
            if (loadList && currentItemsAreEmpty()) refresh()
            return
        }
        cancelRequest("list")
        cancelRequest("filter")
        clearSelection()
        mutableAuthorState.value = RecruitmentAuthorState()
        val kind = board.communityKind
        mutableState.update {
            it.copy(
                board = board,
                communityQuery = kind?.let(communityQueries::getValue) ?: it.communityQuery,
                dutyItems = emptyList(), communityItems = emptyList(), page = 1, totalCount = 0, hasMore = false,
                isLoading = false, isRefreshing = false, isLoadingMore = false,
                listError = null, listErrorIsPagination = false,
                filterCatalog = CommunityRecruitmentFilterCatalog(), isLoadingFilterCatalog = false,
                filterCatalogError = null,
            )
        }
        if (kind != null && (!kind.requiresAuthentication || hasCommunityIdentity)) loadFilterCatalog(kind)
        if (loadList) refresh()
    }

    fun applyDutyFilter(dutyType: String, dutyName: String, position: DutyRecruitmentPosition?) {
        val query = state.value.dutyQuery.copy(
            page = 1, limit = DutyPageSize, dutyType = dutyType.trim(), dutyName = dutyName.trim(),
            position = position?.takeIf { it in DutyRecruitmentPosition.options(dutyType) },
        )
        applyDutyBrowseFilter(DutyRecruitmentBrowseQuery(list = query))
    }

    fun applyDutyBrowseFilter(query: DutyRecruitmentBrowseQuery) {
        if (cleared || state.value.board != RecruitmentBoardKind.Duty) return
        if (!canBrowseExtended && query != DutyRecruitmentBrowseQuery(list = query.list)) {
            setInteractionError(RecruitmentInteractionError.Unavailable)
            return
        }
        val normalized = query.copy(
            list = query.list.copy(page = 1, limit = DutyPageSize, dutyType = query.list.dutyType.trim(), dutyName = query.list.dutyName.trim()),
            positions = query.positions.distinct(), teamComposition = query.teamComposition.trim(),
            targetAreaId = query.targetAreaId.trim(), labelIds = query.labelIds.distinct(), allianceTeamKey = query.allianceTeamKey.trim(),
        )
        if (normalized != browsingState.value.dutyQuery) {
            cancelRequest("list")
            mutableBrowsingState.update { it.copy(dutyQuery = normalized) }
            mutableState.update { it.copy(dutyQuery = normalized.list, dutyItems = emptyList(), page = 1, totalCount = 0, hasMore = false) }
        }
        refresh()
    }

    fun setReviewOrder(order: RolePlayRecruitmentReviewOrder) {
        if (cleared || order == browsingState.value.reviewOrder) return
        if (!canBrowseExtended) {
            setInteractionError(RecruitmentInteractionError.Unavailable)
            return
        }
        mutableBrowsingState.update { it.copy(reviewOrder = order) }
        mutableAuthorState.update { it.copy(reviewAuthors = emptyMap(), subcommentAuthors = emptyMap()) }
        requests.keys.filter { it == "reviews" || it.startsWith("sub:") || it.startsWith("like:") }.toList().forEach(::cancelRequest)
        mutableState.update {
            it.copy(reviews = emptyList(), reviewsPage = 0, hasMoreReviews = false,
                isLoadingReviews = false, isLoadingMoreReviews = false, reviewsError = null,
                subcomments = emptyMap(), loadingSubcommentIds = emptySet(), likingReviewIds = emptySet())
        }
        mutableInteractionState.update {
            it.copy(selectedReviewId = null, reviewsErrorIsPagination = false, subcommentPages = emptyMap(),
                hasMoreSubcommentIds = emptySet(), subcommentErrors = emptyMap(), subcommentErrorIsPaginationIds = emptySet())
        }
        refreshReviews()
    }

    fun applyCommunityFilter(query: CommunityRecruitmentQuery) {
        if (cleared || query.kind != state.value.board.communityKind) return
        val normalized = query.copy(page = 1, limit = CommunityPageSize, keyword = query.keyword.trim())
        communityQueries[query.kind] = normalized
        if (normalized != state.value.communityQuery) {
            cancelRequest("list")
            mutableAuthorState.update { it.copy(communityAuthors = emptyMap()) }
            mutableState.update {
                it.copy(communityQuery = normalized, communityItems = emptyList(), page = 1, totalCount = 0, hasMore = false)
            }
        }
        refresh()
    }

    fun refresh() = loadList(append = false)

    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.isLoading || current.isRefreshing || current.isLoadingMore) return
        loadList(append = true)
    }

    private fun loadList(append: Boolean) {
        if (cleared) return
        val requested = state.value
        if (requested.board == RecruitmentBoardKind.Guild && !hasCommunityIdentity) {
            clearProtectedContent()
            return
        }
        val page = if (append) requested.page + 1 else 1
        val requestedBrowse = browsingState.value.dutyQuery
        val hasContent = !currentItemsAreEmpty()
        mutableState.update {
            it.copy(isLoading = !append && !hasContent, isRefreshing = !append && hasContent,
                isLoadingMore = append, listError = null, listErrorIsPagination = false)
        }
        launchRequest("list", onFailure = { error ->
            mutableState.update { it.copy(listError = error.name, listErrorIsPagination = append) }
        }, onFinish = {
            mutableState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false) }
        }) { request ->
            if (requested.board == RecruitmentBoardKind.Duty) {
                val result = if (service is RecruitmentBrowsingService) {
                    service.fetchDutyRecruitments(requestedBrowse.copy(list = requested.dutyQuery.copy(page = page, limit = DutyPageSize)))
                } else service.fetchDutyRecruitments(requested.dutyQuery.copy(page = page, limit = DutyPageSize))
                if (!request.accept()) return@launchRequest
                mutableState.update { current ->
                    val old = if (append) current.dutyItems else emptyList()
                    val items = (old + result.items).distinctBy { it.id }
                    current.copy(dutyItems = items, communityItems = emptyList(), page = result.currentPage,
                        totalCount = result.totalCount,
                        hasMore = items.size < result.totalCount && result.items.isNotEmpty() && (!append || items.size > old.size))
                }
            } else {
                val query = requested.communityQuery.copy(page = page, limit = CommunityPageSize)
                val rich = (service as? RecruitmentAuthorService)?.fetchCommunityRecruitmentsWithAuthors(query)
                    ?: CommunityRecruitmentAuthorPage(service.fetchCommunityRecruitments(query), emptyMap())
                val result = rich.page
                if (!request.accept()) return@launchRequest
                val oldIds = if (append) state.value.communityItems.map { it.id } else emptyList()
                mutableAuthorState.update { it.copy(communityAuthors = mergeAuthors(
                    if (append) it.communityAuthors else emptyMap(), oldIds, rich.authorUuids, result.items.map { item -> item.id },
                )) }
                mutableState.update { current ->
                    val old = if (append) current.communityItems else emptyList()
                    val items = (old + result.items).distinctBy { it.id }
                    current.copy(communityItems = items, dutyItems = emptyList(), page = result.currentPage,
                        totalCount = result.totalCount,
                        hasMore = result.items.size >= CommunityPageSize && (!append || items.size > old.size))
                }
            }
        }
    }

    fun selectDetail(id: Int) {
        if (cleared) return
        val current = state.value
        if (current.selectedId == id && (current.isLoadingDetail || current.dutyDetail != null || current.communityDetail != null)) return
        clearSelection()
        mutableState.update { it.copy(selectedId = id) }
        loadDetail()
    }

    fun clearSelection() {
        cancelTargetRequests()
        mutableAuthorState.update { it.copy(selectedAuthorUuid = null, reviewAuthors = emptyMap(), subcommentAuthors = emptyMap()) }
        confirmedLikes.clear()
        val revision = interactionState.value.responseSuccessRevision
        mutableInteractionState.value = RecruitmentInteractionState(responseSuccessRevision = revision)
        mutableState.update {
            it.copy(selectedId = null, dutyDetail = null, communityDetail = null, isLoadingDetail = false, detailError = null,
                members = emptyList(), isLoadingMembers = false, membersError = null,
                reviews = emptyList(), reviewsPage = 0, hasMoreReviews = false, isLoadingReviews = false,
                isLoadingMoreReviews = false, reviewsError = null,
                rating = null, isLoadingRating = false, ratingError = null,
                subcomments = emptyMap(), loadingSubcommentIds = emptySet(), likingReviewIds = emptySet(),
                isResponding = false, responseContactInfo = null, responseError = null,
                interactionError = null, error = null, notice = null)
        }
    }

    fun refreshDetail() = loadDetail()
    fun retryDetail() = loadDetail()

    private fun loadDetail() {
        if (cleared) return
        val requested = state.value
        val id = requested.selectedId ?: return
        if (requested.board == RecruitmentBoardKind.Guild && !hasCommunityIdentity) {
            clearProtectedContent()
            return
        }
        val target = requested.board to id
        // Only a read requested after acknowledgement can replace a missing write response contact.
        val refreshesResponseContact = target in respondedTargets
        mutableState.update { it.copy(isLoadingDetail = true, detailError = null) }
        launchRequest("detail", onFailure = { error ->
            mutableState.update { it.copy(detailError = error.name) }
        }, onFinish = { mutableState.update { it.copy(isLoadingDetail = false) } }) { request ->
            if (requested.board == RecruitmentBoardKind.Duty) {
                val rich = (service as? RecruitmentResponseEligibilityService)?.fetchDutyInteractionDetail(id)
                val result = rich?.detail ?: service.fetchDutyRecruitmentDetail(id)
                if (!request.accept()) return@launchRequest
                mutableAuthorState.update { it.copy(selectedAuthorUuid = result.summary.uuid?.takeIf(String::isNotBlank)) }
                mutableInteractionState.update { it.copy(isCurrentUserAuthor = rich?.isCurrentUserAuthor?.takeUnless { authorizationRevoked }) }
                val acknowledged = if (target in respondedTargets) result.copy(
                    isResponded = true,
                    contactInfo = if (refreshesResponseContact) result.contactInfo else respondedTargets[target] ?: result.contactInfo,
                ) else result
                val detail = if (authorizationRevoked) acknowledged.copy(contactInfo = "", isResponded = false) else acknowledged
                mutableState.update { it.copy(dutyDetail = detail, communityDetail = null,
                    responseContactInfo = if (refreshesResponseContact) result.contactInfo.takeIf(String::isNotBlank) else it.responseContactInfo) }
                if (refreshesResponseContact) confirmRefreshedContact(target, result.contactInfo.takeIf(String::isNotBlank))
            } else {
                val kind = requireNotNull(requested.board.communityKind)
                val authorDetail = (service as? RecruitmentAuthorService)?.fetchCommunityDetailWithAuthor(id, kind)
                val rich = authorDetail?.interaction
                    ?: (service as? RecruitmentResponseEligibilityService)?.fetchCommunityInteractionDetail(id, kind)
                val result = rich?.detail ?: service.fetchCommunityRecruitmentDetail(id, kind)
                if (!request.accept()) return@launchRequest
                mutableAuthorState.update { it.copy(selectedAuthorUuid = authorDetail?.authorUuid?.takeIf(String::isNotBlank)) }
                mutableInteractionState.update { it.copy(isCurrentUserAuthor = rich?.isCurrentUserAuthor?.takeUnless { authorizationRevoked }) }
                val fetchedContact = result.summary.beginner?.recruiterContactInfo
                val acknowledged = if (target in respondedTargets) result.markResponded(
                    if (refreshesResponseContact) fetchedContact else respondedTargets[target] ?: fetchedContact,
                ) else result
                val detail = if (authorizationRevoked) acknowledged.copy(summary = acknowledged.summary.copy(
                    beginner = acknowledged.summary.beginner?.copy(isResponded = false, recruiterContactInfo = null),
                )) else acknowledged
                mutableState.update { it.copy(communityDetail = detail, dutyDetail = null,
                    responseContactInfo = if (refreshesResponseContact) fetchedContact?.takeIf(String::isNotBlank) else it.responseContactInfo) }
                if (refreshesResponseContact) confirmRefreshedContact(target, fetchedContact?.takeIf(String::isNotBlank))
                if (requested.board == RecruitmentBoardKind.RolePlay) {
                    refreshMembers()
                    refreshReviews()
                    refreshRating()
                }
            }
        }
    }

    fun openResponseComposer() {
        if (state.value.isResponding || !requireWrite(response = true)) return
        if (!validResponseTarget()) {
            setResponseError(RecruitmentInteractionError.InvalidInput)
            return
        }
        mutableInteractionState.update { it.copy(isResponseComposerOpen = true, responseError = null) }
        mutableState.update { it.copy(responseError = null) }
    }

    /** Reopen a retained draft for viewing or discarding; submission still checks current eligibility. */
    fun resumeResponseComposer() {
        if (interactionState.value.contactDraft.isEmpty()) return
        if (!canInteract) {
            clearProtectedContent()
            setResponseError(RecruitmentInteractionError.Unavailable)
            return
        }
        if (state.value.isResponding) return
        val error = if (canRespond) null else RecruitmentInteractionError.InvalidInput
        mutableInteractionState.update { it.copy(isResponseComposerOpen = true, responseError = error) }
        mutableState.update { it.copy(responseError = error?.name) }
    }

    fun closeResponseComposer() {
        if (!state.value.isResponding) mutableInteractionState.update { it.copy(isResponseComposerOpen = false) }
    }

    fun discardResponseDraft() {
        if (state.value.isResponding) return
        mutableInteractionState.update { it.copy(isResponseComposerOpen = false, contactDraft = "", responseError = null) }
        mutableState.update { it.copy(responseError = null) }
    }

    fun updateResponseDraft(text: String) {
        if (!canInteract || state.value.isResponding) return
        mutableInteractionState.update { it.copy(contactDraft = text, responseError = null) }
        mutableState.update { it.copy(responseError = null) }
    }

    fun submitResponseDraft() = respond(interactionState.value.contactDraft)

    fun respond(contactInfo: String, onSuccess: () -> Unit = {}) {
        if (state.value.isResponding || !requireWrite(response = true)) return
        val normalized = contactInfo.trim()
        if (!validResponseTarget() || normalized.isEmpty() || normalized.length > MaximumContactLength) {
            setResponseError(RecruitmentInteractionError.InvalidInput)
            return
        }
        val requested = state.value
        val id = requireNotNull(requested.selectedId)
        mutableState.update { it.copy(isResponding = true, responseError = null) }
        mutableInteractionState.update { it.copy(responseError = null) }
        launchRequest("response", onFailure = ::setResponseError,
            onFinish = { mutableState.update { it.copy(isResponding = false) } }) { request ->
            if (!checkPendingWrite(response = true)) return@launchRequest
            if (!validResponseTarget()) {
                setResponseError(RecruitmentInteractionError.InvalidInput)
                return@launchRequest
            }
            val returned = when (requested.board) {
                RecruitmentBoardKind.Duty -> service.respondToDutyRecruitment(id, normalized)
                RecruitmentBoardKind.Beginner -> service.respondToBeginnerRecruitment(id, normalized)
                else -> return@launchRequest
            }
            if (!request.accept()) return@launchRequest
            if (!checkPendingWrite(response = true)) return@launchRequest
            respondedTargets[requested.board to id] = returned
            mutableState.update {
                it.copy(responseContactInfo = returned, dutyDetail = it.dutyDetail?.copy(isResponded = true),
                    communityDetail = it.communityDetail?.markResponded(returned),
                    communityItems = it.communityItems.map { summary ->
                        if (summary.id == id) summary.copy(beginner = summary.beginner?.copy(isResponded = true, recruiterContactInfo = returned))
                        else summary
                    }, responseError = null, notice = RecruitmentNotice.ResponseSubmitted)
            }
            mutableInteractionState.update {
                it.copy(isResponseComposerOpen = false, contactDraft = "", responseError = null,
                    responseSuccessRevision = it.responseSuccessRevision + 1, hasRefreshedResponseContact = false)
            }
            // The compatibility callback cannot turn an acknowledged write into a failed submission.
            try { onSuccess() } catch (error: CancellationException) { throw error } catch (_: Throwable) { }
        }
    }

    fun likeReview(review: RolePlayRecruitmentReview) {
        if (review.id in state.value.likingReviewIds || !requireWrite()) return
        if (!isRolePlayDetail() || state.value.reviews.none { it.id == review.id }) {
            setInteractionError(RecruitmentInteractionError.InvalidInput)
            return
        }
        mutableState.update { it.copy(likingReviewIds = it.likingReviewIds + review.id, interactionError = null, error = null) }
        mutableInteractionState.update { it.copy(interactionError = null) }
        launchRequest("like:${review.id}", onFailure = ::setInteractionError, onFinish = {
            mutableState.update { it.copy(likingReviewIds = it.likingReviewIds - review.id) }
        }) { request ->
            if (!checkPendingWrite()) return@launchRequest
            val result = service.likeRolePlayReview(review.id)
            if (!request.accept() || !checkPendingWrite()) return@launchRequest
            mutableState.update { current ->
                current.copy(reviews = current.reviews.map { item ->
                    if (item.id != review.id) item else {
                        val liked = result == 1
                        val delta = if (liked == item.isLiked) 0 else if (liked) 1 else -1
                        val count = (item.likeCount + delta).coerceAtLeast(0)
                        confirmedLikes[item.id] = liked to count
                        item.copy(isLiked = liked, likeCount = count)
                    }
                })
            }
        }
    }

    fun openReviewReplies(review: RolePlayRecruitmentReview) {
        if (!isRolePlayDetail() || state.value.reviews.none { it.id == review.id }) return
        mutableInteractionState.update { it.copy(selectedReviewId = review.id) }
        if (review.id !in interactionState.value.subcommentPages) loadSubcomments(review)
    }

    fun dismissReviewReplies() {
        mutableInteractionState.update { it.copy(selectedReviewId = null) }
    }

    /** Reload the first page; a failed reload retains the confirmed page and replies. */
    fun loadSubcomments(review: RolePlayRecruitmentReview) = loadSubcommentPage(review.id, append = false)

    fun loadMoreSubcomments(rootId: String) {
        if (rootId !in interactionState.value.hasMoreSubcommentIds) return
        loadSubcommentPage(rootId, append = true)
    }

    private fun loadSubcommentPage(rootId: String, append: Boolean) {
        if (!isRolePlayDetail() || state.value.reviews.none { it.id == rootId } || rootId in state.value.loadingSubcommentIds) return
        val nextPage = if (append) (interactionState.value.subcommentPages[rootId] ?: 0) + 1 else 1
        mutableState.update { it.copy(loadingSubcommentIds = it.loadingSubcommentIds + rootId) }
        mutableInteractionState.update { it.copy(subcommentErrors = it.subcommentErrors - rootId,
            subcommentErrorIsPaginationIds = it.subcommentErrorIsPaginationIds - rootId) }
        launchRequest("sub:$rootId", onFailure = { error ->
            mutableInteractionState.update { it.copy(subcommentErrors = it.subcommentErrors + (rootId to error),
                subcommentErrorIsPaginationIds = if (append) it.subcommentErrorIsPaginationIds + rootId else it.subcommentErrorIsPaginationIds - rootId) }
        }, onFinish = {
            mutableState.update { it.copy(loadingSubcommentIds = it.loadingSubcommentIds - rootId) }
        }) { request ->
            val rich = readSubcommentsWithAuthors(rootId, nextPage, ReviewPageSize)
            val page = rich.page
            if (!request.accept()) return@launchRequest
            val old = if (append) state.value.subcomments[rootId].orEmpty() else emptyList()
            mutableAuthorState.update { it.copy(subcommentAuthors = it.subcommentAuthors + (rootId to mergeAuthors(
                if (append) it.subcommentAuthors[rootId].orEmpty() else emptyMap(), old.map { item -> item.id },
                rich.authorUuids, page.items.map { item -> item.id },
            ))) }
            val items = (old + page.items).distinctBy { it.id }
            val hasMore = page.hasMore && page.items.isNotEmpty() && (!append || items.size > old.size)
            mutableState.update { it.copy(subcomments = it.subcomments + (rootId to items)) }
            mutableInteractionState.update {
                it.copy(subcommentPages = it.subcommentPages + (rootId to page.page),
                    hasMoreSubcommentIds = if (hasMore) it.hasMoreSubcommentIds + rootId else it.hasMoreSubcommentIds - rootId)
            }
        }
    }

    fun refreshReviews() = loadReviews(append = false)

    fun loadMoreReviews() {
        val current = state.value
        if (!current.hasMoreReviews || current.isLoadingReviews || current.isLoadingMoreReviews) return
        loadReviews(append = true)
    }

    private fun loadReviews(append: Boolean) {
        if (!isRolePlayDetail()) return
        val requested = state.value
        val id = requireNotNull(requested.selectedId)
        val order = browsingState.value.reviewOrder
        mutableState.update { it.copy(isLoadingReviews = !append, isLoadingMoreReviews = append, reviewsError = null) }
        mutableInteractionState.update { it.copy(reviewsErrorIsPagination = false) }
        launchRequest("reviews", onFailure = { error ->
            mutableState.update { it.copy(reviewsError = error.name) }
            mutableInteractionState.update { it.copy(reviewsErrorIsPagination = append) }
        }, onFinish = {
            mutableState.update { it.copy(isLoadingReviews = false, isLoadingMoreReviews = false) }
        }) { request ->
            val requestedPage = if (append) requested.reviewsPage + 1 else 1
            val query = RolePlayRecruitmentReviewQuery(id, requestedPage, ReviewPageSize, order)
            val rich = (service as? RecruitmentAuthorService)?.fetchRolePlayReviewsWithAuthors(query)
                ?: RolePlayReviewAuthorPage(
                    if (service is RecruitmentBrowsingService) service.fetchRolePlayReviews(query)
                    else service.fetchRolePlayReviews(id, requestedPage, ReviewPageSize), emptyMap(),
                )
            val page = rich.page
            if (!request.accept()) return@launchRequest
            val previews = attachSubcommentPreviews(page.items.distinctBy { it.id })
            val fetched = previews.reviews
            if (!request.accept()) return@launchRequest
            val old = if (append) state.value.reviews else emptyList()
            val reviews = (old + fetched).distinctBy { it.id }.map { review ->
                confirmedLikes[review.id]?.let { (liked, count) -> review.copy(isLiked = liked, likeCount = count) } ?: review
            }
            val ids = reviews.mapTo(mutableSetOf()) { it.id }
            mutableAuthorState.update { authors ->
                val children = authors.subcommentAuthors.filterKeys { it in ids }.toMutableMap()
                fetched.forEach { review ->
                    // Existing reply pages win over previews, including pages with no known author.
                    if (review.id !in state.value.subcomments) {
                        val candidates = rich.previewAuthorUuids[review.id].orEmpty() + previews.authors[review.id].orEmpty()
                        val replyIds = review.childPreviewReplies.mapTo(hashSetOf()) { it.id }
                        children[review.id] = candidates.filterKeys { it in replyIds }
                    }
                }
                authors.copy(reviewAuthors = mergeAuthors(
                    if (append) authors.reviewAuthors else emptyMap(), old.map { it.id }, rich.authorUuids, page.items.map { it.id },
                ), subcommentAuthors = children)
            }
            requests.keys.filter { (it.startsWith("sub:") || it.startsWith("like:")) && it.substringAfter(':') !in ids }
                .toList().forEach(::cancelRequest)
            mutableState.update { current ->
                val replies = current.subcomments.filterKeys { it in ids }.toMutableMap()
                fetched.forEach { review ->
                    if (review.id !in replies) replies[review.id] = review.childPreviewReplies
                }
                current.copy(reviews = reviews, reviewsPage = page.page,
                    hasMoreReviews = page.hasMore && page.items.isNotEmpty() && (!append || reviews.size > old.size),
                    subcomments = replies, loadingSubcommentIds = current.loadingSubcommentIds.intersect(ids),
                    likingReviewIds = current.likingReviewIds.intersect(ids))
            }
            mutableInteractionState.update {
                it.copy(selectedReviewId = it.selectedReviewId?.takeIf(ids::contains),
                    subcommentPages = it.subcommentPages.filterKeys(ids::contains),
                    hasMoreSubcommentIds = it.hasMoreSubcommentIds.intersect(ids),
                    subcommentErrors = it.subcommentErrors.filterKeys(ids::contains),
                    subcommentErrorIsPaginationIds = it.subcommentErrorIsPaginationIds.intersect(ids))
            }
        }
    }

    fun refreshMembers() {
        if (!isRolePlayDetail()) return
        val id = requireNotNull(state.value.selectedId)
        mutableState.update { it.copy(isLoadingMembers = true, membersError = null) }
        launchRequest("members", onFailure = { error -> mutableState.update { it.copy(membersError = error.name) } },
            onFinish = { mutableState.update { it.copy(isLoadingMembers = false) } }) { request ->
            val result = service.fetchRolePlayMembers(id)
            if (request.accept()) mutableState.update { it.copy(members = result) }
        }
    }

    fun refreshRating() {
        if (!isRolePlayDetail()) return
        val id = requireNotNull(state.value.selectedId)
        mutableState.update { it.copy(isLoadingRating = true, ratingError = null) }
        launchRequest("rating", onFailure = { error -> mutableState.update { it.copy(ratingError = error.name) } },
            onFinish = { mutableState.update { it.copy(isLoadingRating = false) } }) { request ->
            val result = service.fetchRolePlayRating(id)
            if (request.accept()) mutableState.update { it.copy(rating = result) }
        }
    }

    fun loadCatalogs() {
        if (cleared || state.value.isLoadingCatalogs) return
        mutableState.update { it.copy(isLoadingCatalogs = true, catalogsError = null) }
        launchRequest("catalogs", onFailure = { error -> mutableState.update { it.copy(catalogsError = error.name) } },
            onFinish = { mutableState.update { it.copy(isLoadingCatalogs = false) } }) { request ->
            if (service is RecruitmentBrowsingService) {
                val result = service.fetchDutyFilterCatalog()
                if (request.accept()) {
                    mutableBrowsingState.update { it.copy(dutyFilterCatalog = result) }
                    mutableState.update { it.copy(catalogs = result.catalogs) }
                }
            } else {
                val result = service.fetchCatalogs()
                if (request.accept()) mutableState.update { it.copy(catalogs = result) }
            }
        }
    }

    fun retryFilterCatalog() {
        state.value.board.communityKind?.let(::loadFilterCatalog)
    }

    private fun loadFilterCatalog(kind: CommunityRecruitmentKind) {
        if (cleared || state.value.isLoadingFilterCatalog || (kind.requiresAuthentication && !hasCommunityIdentity)) return
        mutableState.update { it.copy(isLoadingFilterCatalog = true, filterCatalogError = null) }
        launchRequest("filter", onFailure = { error -> mutableState.update { it.copy(filterCatalogError = error.name) } },
            onFinish = { mutableState.update { it.copy(isLoadingFilterCatalog = false) } }) { request ->
            val result = service.fetchCommunityFilterCatalog(kind)
            if (request.accept()) mutableState.update { it.copy(filterCatalog = result) }
        }
    }

    fun clearResponseError() {
        mutableState.update { it.copy(responseError = null) }
        mutableInteractionState.update { it.copy(responseError = null) }
    }

    fun clearNotice() {
        mutableState.update { it.copy(error = null, notice = null, interactionError = null) }
        mutableInteractionState.update { it.copy(interactionError = null) }
    }

    /** Revoke transient credentials-dependent state; public reads may be retried on this model. */
    fun clearProtectedContent() {
        authorizationRevoked = true
        requests.keys.toList().forEach(::cancelRequest)
        val selected = state.value.selectedId
        clearSelection()
        mutableAuthorState.value = RecruitmentAuthorState()
        respondedTargets.clear()
        mutableInteractionState.value = RecruitmentInteractionState()
        mutableState.update {
            it.copy(selectedId = selected,
                communityItems = if (it.board == RecruitmentBoardKind.Guild) emptyList() else it.communityItems.map { summary ->
                    summary.copy(beginner = summary.beginner?.copy(isResponded = false, recruiterContactInfo = null))
                },
                filterCatalog = if (it.board == RecruitmentBoardKind.Guild) CommunityRecruitmentFilterCatalog() else it.filterCatalog,
                hasMore = if (it.board == RecruitmentBoardKind.Guild) false else it.hasMore,
                totalCount = if (it.board == RecruitmentBoardKind.Guild) 0 else it.totalCount,
                isLoading = false, isRefreshing = false, isLoadingMore = false,
                isLoadingCatalogs = false, isLoadingFilterCatalog = false)
        }
    }

    override fun onCleared() {
        clearProtectedContent()
        cleared = true
        super.onCleared()
    }

    private fun validResponseTarget(): Boolean {
        if (interactionState.value.isCurrentUserAuthor != false) return false
        val current = state.value
        val id = current.selectedId ?: return false
        if ((current.board to id) in respondedTargets) return false
        return when (current.board) {
            RecruitmentBoardKind.Duty -> current.dutyDetail?.let { it.summary.id == id && !it.isResponded } == true
            RecruitmentBoardKind.Beginner -> current.communityDetail?.let {
                it.summary.id == id && it.summary.kind == CommunityRecruitmentKind.Beginner && it.summary.beginner?.isResponded == false
            } == true
            else -> false
        }
    }

    private fun isRolePlayDetail(): Boolean = !cleared && state.value.let {
        it.board == RecruitmentBoardKind.RolePlay && it.selectedId != null &&
            it.communityDetail?.summary?.kind == CommunityRecruitmentKind.RolePlay &&
            it.communityDetail.summary.id == it.selectedId
    }

    private fun requireWrite(response: Boolean = false): Boolean {
        if (cleared) return false
        if (canInteract) return true
        val error = if (authorizationRevoked) RecruitmentInteractionError.AuthenticationRequired else RecruitmentInteractionError.Unavailable
        if (response) setResponseError(error) else setInteractionError(error)
        return false
    }

    private fun checkPendingWrite(response: Boolean = false): Boolean {
        if (canInteract) return true
        clearProtectedContent()
        if (response) setResponseError(RecruitmentInteractionError.Unavailable)
        else setInteractionError(RecruitmentInteractionError.Unavailable)
        return false
    }

    private fun setResponseError(error: RecruitmentInteractionError) {
        mutableInteractionState.update { it.copy(responseError = error) }
        mutableState.update { it.copy(responseError = error.name) }
    }

    private fun setInteractionError(error: RecruitmentInteractionError) {
        mutableInteractionState.update { it.copy(interactionError = error) }
        mutableState.update { it.copy(interactionError = error.name, error = error.name) }
    }

    private fun confirmRefreshedContact(target: Pair<RecruitmentBoardKind, Int>, contact: String?) {
        respondedTargets[target] = contact
        mutableInteractionState.update { it.copy(hasRefreshedResponseContact = true) }
    }

    private fun CommunityRecruitmentDetail.markResponded(contact: String?): CommunityRecruitmentDetail = copy(
        summary = summary.copy(beginner = summary.beginner?.copy(isResponded = true, recruiterContactInfo = contact)),
    )

    private suspend fun readSubcommentsWithAuthors(rootId: String, page: Int, limit: Int): RolePlaySubcommentAuthorPage =
        (service as? RecruitmentAuthorService)?.fetchRolePlaySubcommentsWithAuthors(rootId, page, limit)
            ?: RolePlaySubcommentAuthorPage(service.fetchRolePlaySubcomments(rootId, page, limit), emptyMap())

    private data class ReviewPreviews(
        val reviews: List<RolePlayRecruitmentReview>,
        val authors: Map<String, Map<String, String>>,
    )

    private suspend fun attachSubcommentPreviews(reviews: List<RolePlayRecruitmentReview>): ReviewPreviews = supervisorScope {
        val previews = reviews.filter { it.childCount > 0 && it.id !in state.value.subcomments }.map { review ->
            async {
                val replies = try {
                    readSubcommentsWithAuthors(review.id, 1, PreviewReplyCount)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: DutyRecruitmentException.AuthenticationRequired) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                review.id to replies
            }
        }.awaitAll().toMap()
        ReviewPreviews(
            reviews.map { review -> review.copy(childPreviewReplies = state.value.subcomments[review.id]
                ?: previews[review.id]?.page?.items?.distinctBy { it.id } ?: review.childPreviewReplies) },
            previews.mapValues { it.value?.authorUuids.orEmpty() },
        )
    }

    private inner class Request(private val key: String, private val revision: Long) {
        fun isCurrent(): Boolean = !cleared && requests[key] == revision
        suspend fun accept(): Boolean {
            currentCoroutineContext().ensureActive()
            return isCurrent()
        }
    }

    private fun launchRequest(
        key: String,
        onFailure: (RecruitmentInteractionError) -> Unit,
        onFinish: () -> Unit,
        block: suspend (Request) -> Unit,
    ) {
        if (cleared) return
        cancelRequest(key)
        val revision = ++nextRequest
        requests[key] = revision
        val request = Request(key, revision)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (request.isCurrent()) {
                    val type = if (error is DutyRecruitmentException.AuthenticationRequired) {
                        clearProtectedContent()
                        RecruitmentInteractionError.AuthenticationRequired
                    } else RecruitmentInteractionError.Failed
                    onFailure(type)
                }
            } finally {
                if (request.isCurrent()) {
                    onFinish()
                    requests.remove(key)
                    jobs.remove(key)
                }
            }
        }
        jobs[key] = job
        job.start()
    }

    private fun cancelRequest(key: String) {
        requests.remove(key)
        jobs.remove(key)?.cancel()
    }

    private fun cancelTargetRequests() {
        requests.keys.filter { it !in setOf("list", "catalogs", "filter") }.toList().forEach(::cancelRequest)
    }

    private fun currentItemsAreEmpty(): Boolean = state.value.let {
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

/** First displayed item wins, even when its author was unknown. */
private fun <K> mergeAuthors(old: Map<K, String>, oldIds: List<K>, incoming: Map<K, String>, incomingIds: List<K>): Map<K, String> {
    val existing = oldIds.toSet()
    val valid = incomingIds.toSet()
    return old + incoming.filter { (id, uuid) -> id !in existing && id in valid && uuid.isNotBlank() }
}
