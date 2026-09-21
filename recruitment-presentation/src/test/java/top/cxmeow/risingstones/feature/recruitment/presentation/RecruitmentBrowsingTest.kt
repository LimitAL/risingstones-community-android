package top.cxmeow.risingstones.feature.recruitment.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecruitmentBrowsingTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun extendedFilterUsesTheRichCatalogAndRetainsEveryConditionForPagination() = runTest {
        val service = BrowsingTestService()
        val model = DutyRecruitmentViewModel(service)
        advanceUntilIdle()
        assertTrue(model.canBrowseExtended)
        assertEquals("Synthetic label", model.browsingState.value.dutyFilterCatalog?.labels?.single()?.name)
        val query = DutyRecruitmentBrowseQuery(
            list = DutyRecruitmentListQuery(dutyName = " Omega ", dutyType = " High-end "),
            positions = listOf(DutyRecruitmentPosition.MainTank, DutyRecruitmentPosition.Healer1, DutyRecruitmentPosition.MainTank),
            teamComposition = "满编小队", targetAreaId = "synthetic-area", labelIds = listOf("label", "label"),
            allianceTeamKey = "synthetic-team",
        )
        model.applyDutyBrowseFilter(query)
        assertTrue(model.state.value.dutyItems.isEmpty())
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        val sent = service.dutyQueries.last()
        assertEquals(2, sent.list.page)
        assertEquals(20, sent.list.limit)
        assertEquals("Omega", sent.list.dutyName)
        assertEquals("High-end", sent.list.dutyType)
        assertEquals(listOf(DutyRecruitmentPosition.MainTank, DutyRecruitmentPosition.Healer1), sent.positions)
        assertEquals("满编小队", sent.teamComposition)
        assertEquals("synthetic-area", sent.targetAreaId)
        assertEquals(listOf("label"), sent.labelIds)
        assertEquals("synthetic-team", sent.allianceTeamKey)
        assertTrue(service.base.dutyQueries.isEmpty())
        assertEquals(listOf(1, 2), model.state.value.dutyItems.map { it.id })
    }

    @Test fun legacyDutyFilterRemainsSinglePositionAndClearsPreviouslyExtendedConditions() = runTest {
        val service = BrowsingTestService()
        val model = DutyRecruitmentViewModel(service)
        advanceUntilIdle()
        model.applyDutyBrowseFilter(DutyRecruitmentBrowseQuery(targetAreaId = "synthetic-area"))
        advanceUntilIdle()
        model.applyDutyFilter("High-end", "Omega", DutyRecruitmentPosition.Healer1)
        advanceUntilIdle()
        val sent = service.dutyQueries.last()
        assertEquals(listOf(DutyRecruitmentPosition.Healer1), sent.positions)
        assertEquals(DutyRecruitmentPosition.Healer1, model.state.value.dutyQuery.position)
        assertEquals("", sent.targetAreaId)
    }

    @Test fun serviceWithoutExtensionDoesNotPretendToApplyUnsupportedFiltersOrOrdering() = runTest {
        val service = FakeDutyRecruitmentService()
        val model = DutyRecruitmentViewModel(service)
        advanceUntilIdle()
        val old = model.browsingState.value
        model.applyDutyBrowseFilter(DutyRecruitmentBrowseQuery(targetAreaId = "synthetic-area"))
        model.setReviewOrder(RolePlayRecruitmentReviewOrder.ScoreAscending)
        advanceUntilIdle()
        assertFalse(model.canBrowseExtended)
        assertEquals(old, model.browsingState.value)
        assertEquals(1, service.dutyQueries.size)
        assertEquals(RecruitmentInteractionError.Unavailable, model.interactionState.value.interactionError)
    }

    @Test fun failedRichCatalogRefreshRetainsConfirmedCatalog() = runTest {
        val service = BrowsingTestService()
        val model = DutyRecruitmentViewModel(service, false)
        advanceUntilIdle()
        val old = model.browsingState.value.dutyFilterCatalog
        service.failCatalog = true
        model.loadCatalogs(); model.loadCatalogs()
        advanceUntilIdle()
        assertEquals(old, model.browsingState.value.dutyFilterCatalog)
        assertEquals("Failed", model.state.value.catalogsError)
        assertFalse(model.state.value.isLoadingCatalogs)
    }

    @Test fun reviewOrderUsesLatestInitiallyAndOldOrderingCannotOverwriteTheNextSelection() = runTest {
        val delayed = CompletableDeferred<Unit>()
        val service = BrowsingTestService()
        service.reviews = { query ->
            if (query.order == RolePlayRecruitmentReviewOrder.Latest) withContext(NonCancellable) { delayed.await() }
            RolePlayRecruitmentReviewPage(listOf(review(query.order.name).copy(childCount = 0)), query.page, false)
        }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        runCurrent()
        assertEquals(RolePlayRecruitmentReviewOrder.Latest, service.reviewQueries.single().order)
        model.setReviewOrder(RolePlayRecruitmentReviewOrder.ScoreDescending)
        assertTrue(model.state.value.reviews.isEmpty())
        runCurrent()
        delayed.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("ScoreDescending"), model.state.value.reviews.map { it.id })
        assertEquals(RolePlayRecruitmentReviewOrder.ScoreDescending, model.browsingState.value.reviewOrder)
        assertNull(model.state.value.reviewsError)
    }

    @Test fun failedReviewRefreshRetainsPageAndNextPageUsesCurrentOrderExactlyOnce() = runTest {
        var fail = false
        val pending = CompletableDeferred<Unit>()
        val service = BrowsingTestService()
        service.reviews = { query ->
            if (fail && query.page == 1) { pending.await(); error("private response") }
            RolePlayRecruitmentReviewPage(listOf(review("page-${query.page}").copy(childCount = 0)), query.page, true)
        }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        model.setReviewOrder(RolePlayRecruitmentReviewOrder.Hottest)
        advanceUntilIdle()
        model.loadMoreReviews(); model.loadMoreReviews()
        advanceUntilIdle()
        fail = true
        model.refreshReviews(); model.loadMoreReviews()
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.reviewsPage)
        assertEquals(listOf("page-1", "page-2"), model.state.value.reviews.map { it.id })
        model.loadMoreReviews()
        advanceUntilIdle()
        assertEquals(3, service.reviewQueries.last().page)
        assertEquals(RolePlayRecruitmentReviewOrder.Hottest, service.reviewQueries.last().order)
        assertEquals(1, service.reviewQueries.count { it.page == 2 })
    }

    @Test fun reviewRetryDistinguishesFailedRefreshFromFailedPagination() = runTest {
        var failingPage: Int? = null
        val service = BrowsingTestService()
        service.reviews = { query ->
            if (query.page == failingPage) error("unavailable")
            RolePlayRecruitmentReviewPage(listOf(review("page-${query.page}").copy(childCount = 0)), query.page, true)
        }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        failingPage = 2
        model.loadMoreReviews()
        advanceUntilIdle()
        assertTrue(model.interactionState.value.reviewsErrorIsPagination)
        assertEquals(1, model.state.value.reviewsPage)
        failingPage = null
        model.loadMoreReviews()
        advanceUntilIdle()
        assertFalse(model.interactionState.value.reviewsErrorIsPagination)
        assertEquals(2, model.state.value.reviewsPage)
        failingPage = 1
        model.refreshReviews()
        advanceUntilIdle()
        assertFalse(model.interactionState.value.reviewsErrorIsPagination)
        assertEquals(2, model.state.value.reviewsPage)
        assertEquals(listOf(1, 2, 2, 1), service.reviewQueries.map { it.page })
    }

    @Test fun reviewEmptyPageStopsPaginationEvenWhenServerClaimsMore() = runTest {
        val service = BrowsingTestService()
        service.reviews = { query -> RolePlayRecruitmentReviewPage(
            if (query.page == 1) listOf(review("one").copy(childCount = 0), review("one").copy(childCount = 0)) else emptyList(),
            query.page, true,
        ) }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        assertEquals(1, model.state.value.reviews.size)
        model.loadMoreReviews()
        advanceUntilIdle()
        assertFalse(model.state.value.hasMoreReviews)
    }

    @Test fun currentAuthorUnknownAuthorAndLegacyEligibilityNeverIssueResponseWrites() = runTest {
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
        }
        for (author in listOf(true, null)) {
            val model = DutyRecruitmentViewModel(eligible(service, author), false)
            model.selectDetail(1)
            advanceUntilIdle()
            assertTrue(model.canWrite)
            assertFalse(model.canRespond)
            model.openResponseComposer(); model.respond("example")
            advanceUntilIdle()
            assertFalse(model.interactionState.value.isResponseComposerOpen)
        }
        val legacy = DutyRecruitmentViewModel(service, false)
        legacy.selectDetail(1)
        advanceUntilIdle()
        assertFalse(legacy.canRespond)
        legacy.respond("example")
        advanceUntilIdle()
        assertEquals(0, writes)
    }

    @Test fun unknownAuthorCanBeRetriedWithoutDroppingDetailAndRespondingRequiresFalse() = runTest {
        var author: Boolean? = null
        var fail = false
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentResponseEligibilityService by eligible(base) {
            override suspend fun fetchDutyInteractionDetail(id: Int): DutyRecruitmentInteractionDetail {
                if (fail) error("unavailable")
                return DutyRecruitmentInteractionDetail(base.fetchDutyRecruitmentDetail(id), author)
            }
        }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectDetail(1)
        advanceUntilIdle()
        assertFalse(model.canRespond)
        fail = true
        model.retryDetail()
        advanceUntilIdle()
        assertEquals(1, model.state.value.dutyDetail?.summary?.id)
        assertFalse(model.canRespond)
        fail = false; author = false
        model.retryDetail()
        advanceUntilIdle()
        assertTrue(model.canRespond)
        model.respond("example")
        advanceUntilIdle()
        assertFalse(model.canRespond)
        assertEquals(1L, model.interactionState.value.responseSuccessRevision)
    }
}

private class BrowsingTestService(
    val base: FakeDutyRecruitmentService = FakeDutyRecruitmentService(),
) : RecruitmentBrowsingService, RecruitmentResponseEligibilityService by eligible(base) {
    val dutyQueries = mutableListOf<DutyRecruitmentBrowseQuery>()
    val reviewQueries = mutableListOf<RolePlayRecruitmentReviewQuery>()
    var failCatalog = false
    var reviews: suspend (RolePlayRecruitmentReviewQuery) -> RolePlayRecruitmentReviewPage = { query ->
        RolePlayRecruitmentReviewPage(listOf(review("review-1").copy(childCount = 0)), query.page, false)
    }
    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentBrowseQuery): DutyRecruitmentListPage {
        dutyQueries += query
        return DutyRecruitmentListPage(listOf(dutySummary(query.list.page)), 10, query.list.page)
    }
    override suspend fun fetchDutyFilterCatalog(): DutyRecruitmentFilterCatalog {
        if (failCatalog) error("catalog unavailable")
        return DutyRecruitmentFilterCatalog(base.fetchCatalogs(), listOf(DutyRecruitmentLabel("label", "Synthetic label", 1)), emptyList())
    }
    override suspend fun fetchRolePlayReviews(query: RolePlayRecruitmentReviewQuery): RolePlayRecruitmentReviewPage {
        reviewQueries += query
        return reviews(query)
    }
}
