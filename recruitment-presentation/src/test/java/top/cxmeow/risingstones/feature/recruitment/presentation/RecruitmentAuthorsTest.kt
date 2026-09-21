package top.cxmeow.risingstones.feature.recruitment.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecruitmentAuthorsTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun listAuthorsStayAlignedWithFirstDisplayedItemsAndFailedRefresh() = runTest {
        val service = AuthorRecruitmentService()
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.Beginner); advanceUntilIdle()
        assertEquals("author-1", model.authorState.value.communityAuthors[1])
        model.loadMore(); advanceUntilIdle()
        assertEquals("author-1", model.authorState.value.communityAuthors[1])
        assertEquals("new-author", model.authorState.value.communityAuthors[21])
        assertEquals(21, model.state.value.communityItems.size)
        service.failList = true
        model.refresh(); advanceUntilIdle()
        assertEquals(21, model.authorState.value.communityAuthors.size)
        service.failList = false
        service.listAuthors = false
        model.refresh(); advanceUntilIdle()
        assertEquals(20, model.state.value.communityItems.size)
        assertTrue(model.authorState.value.communityAuthors.isEmpty())
    }

    @Test fun detailAndPreviewAuthorsDoNotReplaceEligibilityOrTriggerDuplicateReads() = runTest {
        val service = AuthorRecruitmentService()
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false)
        model.selectDetail(14); advanceUntilIdle()
        assertEquals("owner-14", model.authorState.value.selectedAuthorUuid)
        assertEquals(false, model.interactionState.value.isCurrentUserAuthor)
        assertEquals(mapOf("root" to "review-author"), model.authorState.value.reviewAuthors)
        assertEquals(mapOf("child" to "preview-author"), model.authorState.value.subcommentAuthors["root"])
        assertEquals(listOf(14), service.detailReads)
        assertEquals(listOf(1 to 3), service.replyReads)
        assertEquals(1, service.reviewReads)
    }

    @Test fun pagedReplyAuthorsWinOverRootRefreshAndDuplicatePagesKeepTheirOriginalAuthor() = runTest {
        val service = AuthorRecruitmentService()
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14); advanceUntilIdle()
        model.openReviewReplies(model.state.value.reviews.single()); advanceUntilIdle()
        assertEquals("page-author", model.authorState.value.subcommentAuthors["root"]?.get("child"))
        model.loadMoreSubcomments("root"); advanceUntilIdle()
        assertEquals(mapOf("child" to "page-author", "next" to "next-author"), model.authorState.value.subcommentAuthors["root"])
        val calls = service.replyReads.size
        model.refreshReviews(); advanceUntilIdle()
        assertEquals(calls, service.replyReads.size)
        assertEquals(2, model.authorState.value.subcommentAuthors["root"]?.size)
        service.failReplies = true
        model.loadSubcomments(model.state.value.reviews.single()); advanceUntilIdle()
        assertEquals(2, model.authorState.value.subcommentAuthors["root"]?.size)
        service.failReplies = false
        service.replyAuthors = false
        model.loadSubcomments(model.state.value.reviews.single()); advanceUntilIdle()
        assertTrue(model.authorState.value.subcommentAuthors["root"].orEmpty().isEmpty())
        assertEquals(listOf("child"), model.state.value.subcomments["root"]?.map { it.id })
    }

    @Test fun switchedDetailRejectsLateOwnerAndClearingSelectionRetainsOnlyListAuthors() = runTest {
        val delayed = CompletableDeferred<Unit>()
        val service = AuthorRecruitmentService().apply { detailDelay = delayed }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.Beginner); advanceUntilIdle()
        model.selectDetail(14); runCurrent()
        model.selectDetail(15); runCurrent()
        delayed.complete(Unit); advanceUntilIdle()
        assertEquals(15, model.state.value.communityDetail?.summary?.id)
        assertEquals("owner-15", model.authorState.value.selectedAuthorUuid)
        model.clearSelection()
        assertNull(model.authorState.value.selectedAuthorUuid)
        assertTrue(model.authorState.value.reviewAuthors.isEmpty())
        assertEquals(20, model.authorState.value.communityAuthors.size)
        model.selectBoard(RecruitmentBoardKind.Other, false)
        assertTrue(model.authorState.value.communityAuthors.isEmpty())
    }

    @Test fun filterAndRevocationClearAuthorsAndRejectLateListCompletion() = runTest {
        val service = AuthorRecruitmentService()
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.Beginner); advanceUntilIdle()
        val delayed = CompletableDeferred<Unit>()
        service.listDelay = delayed
        model.applyCommunityFilter(CommunityRecruitmentQuery(CommunityRecruitmentKind.Beginner, identity = "1")); runCurrent()
        assertTrue(model.authorState.value.communityAuthors.isEmpty())
        model.clearProtectedContent()
        delayed.complete(Unit); advanceUntilIdle()
        assertEquals(RecruitmentAuthorState(), model.authorState.value)
    }

    @Test fun legacyServiceRetainsItsExistingReadPathAndDoesNotInventMissingAuthors() = runTest {
        val model = DutyRecruitmentViewModel(FakeDutyRecruitmentService(), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay); advanceUntilIdle()
        model.selectDetail(14); advanceUntilIdle()
        assertEquals(RecruitmentAuthorState(), model.authorState.value.copy(subcommentAuthors = emptyMap()))
        model.selectBoard(RecruitmentBoardKind.Duty, false); model.selectDetail(1); advanceUntilIdle()
        assertEquals("author", model.authorState.value.selectedAuthorUuid)
    }
}

private class AuthorRecruitmentService(
    private val base: FakeDutyRecruitmentService = FakeDutyRecruitmentService(),
) : RecruitmentAuthorService, DutyRecruitmentService by base {
    var failList = false
    var listAuthors = true
    var failReplies = false
    var replyAuthors = true
    var detailDelay: CompletableDeferred<Unit>? = null
    var listDelay: CompletableDeferred<Unit>? = null
    val detailReads = mutableListOf<Int>()
    var reviewReads = 0
    val replyReads = mutableListOf<Pair<Int, Int>>()

    override suspend fun fetchCommunityRecruitmentsWithAuthors(query: CommunityRecruitmentQuery): CommunityRecruitmentAuthorPage {
        withContext(NonCancellable) { listDelay?.await() }
        if (failList) error("Fixture failure")
        val ids = if (query.page == 1) (1..20).toList() else listOf(1, 21)
        val authors = if (!listAuthors) emptyMap() else ids.associateWith { if (query.page == 1) "author-$it" else if (it == 1) "wrong-duplicate" else "new-author" }
        return CommunityRecruitmentAuthorPage(CommunityRecruitmentPage(ids.map { beginnerSummary().copy(id = it) }, 21, query.page), authors)
    }

    override suspend fun fetchCommunityDetailWithAuthor(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentAuthorDetail {
        detailReads += id
        if (id == 14) withContext(NonCancellable) { detailDelay?.await() }
        val detail = base.fetchCommunityRecruitmentDetail(id, kind)
        return CommunityRecruitmentAuthorDetail(CommunityRecruitmentInteractionDetail(detail.copy(summary = detail.summary.copy(id = id)), false), "owner-$id")
    }

    override suspend fun fetchRolePlayReviewsWithAuthors(query: RolePlayRecruitmentReviewQuery): RolePlayReviewAuthorPage {
        reviewReads++
        return RolePlayReviewAuthorPage(RolePlayRecruitmentReviewPage(listOf(review("root")), query.page, false), mapOf("root" to "review-author"))
    }

    override suspend fun fetchRolePlaySubcommentsWithAuthors(rootParentId: String, page: Int, limit: Int): RolePlaySubcommentAuthorPage {
        replyReads += page to limit
        if (failReplies) error("Fixture failure")
        val ids = if (page == 1) listOf("child") else listOf("child", "next")
        val authors = if (!replyAuthors) emptyMap() else when {
            limit == 3 -> mapOf("child" to "preview-author")
            page == 1 -> mapOf("child" to "page-author")
            else -> mapOf("child" to "wrong-duplicate", "next" to "next-author")
        }
        return RolePlaySubcommentAuthorPage(RolePlayRecruitmentSubcommentPage(ids.map {
            RolePlayRecruitmentSubcomment(it, "Same name", null, "Same recipient", "Reply", emptyList())
        }, page, page == 1), authors)
    }
}
