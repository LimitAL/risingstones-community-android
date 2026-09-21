package top.cxmeow.risingstones.feature.recruitment.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
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
class RolePlayDirectoryViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun constructionAndParentBindingDoNotReadAndExplicitOpenIsIdempotent() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.setParent(10)
        advanceUntilIdle()
        assertTrue(service.memberQueries.isEmpty())
        assertTrue(service.activityQueries.isEmpty())
        model.open(10, RolePlayDirectorySection.Members)
        model.open(10, RolePlayDirectorySection.Members)
        assertTrue(model.state.value.members.isLoading)
        advanceUntilIdle()
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertEquals(1, service.memberQueries.size)
        assertTrue(model.state.value.members.hasLoaded)
        assertEquals(0, service.base.dutyQueries.size)
    }

    @Test fun oldServiceIsExplicitlyUnsupportedWithoutCallingAnyExistingMemberApi() = runTest {
        val model = RolePlayDirectoryViewModel(FakeDutyRecruitmentService())
        assertFalse(model.supportsDirectory)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertEquals(RecruitmentInteractionError.Unavailable, model.state.value.members.error)
        assertFalse(model.state.value.members.isLoading)
        model.selectSection(RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        assertEquals(RecruitmentInteractionError.Unavailable, model.state.value.activities.error)
    }

    @Test fun memberPaginationReusesOnlyTheFirstPageCursorAndDeduplicatesRows() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.loadMore(); model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), model.state.value.members.items.map { it.id })
        assertEquals(2, model.state.value.members.page)
        assertEquals("first-snapshot", service.memberQueries.last().pageTime)
        model.loadMore()
        advanceUntilIdle()
        assertEquals("first-snapshot", service.memberQueries.last().pageTime)
        assertEquals(listOf(1, 2, 3), service.memberQueries.map { it.page })
        assertFalse(model.state.value.members.hasMore)
    }

    @Test fun failedMemberRefreshPreservesPageCursorSelectionAndConfirmedContent() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.loadMore(); model.selectMember(1)
        advanceUntilIdle()
        val old = model.state.value.members
        assertEquals(1, old.detail?.id)
        service.memberPage = { error("synthetic failure") }
        model.refresh()
        advanceUntilIdle()
        assertEquals(old.items, model.state.value.members.items)
        assertEquals(old.detail, model.state.value.members.detail)
        assertEquals(2, model.state.value.members.page)
        assertEquals(1, model.state.value.members.selectedId)
        assertEquals(RecruitmentInteractionError.Failed, model.state.value.members.error)
        assertFalse(model.state.value.members.errorIsPagination)
        assertNull(service.memberQueries.last().pageTime)
        service.memberPage = { RolePlayMemberPage(emptyList(), it.page, false, "unused") }
        model.loadMore()
        advanceUntilIdle()
        assertEquals(3, service.memberQueries.last().page)
        assertEquals("first-snapshot", service.memberQueries.last().pageTime)
    }

    @Test fun memberPaginationFailureRetriesTheSamePageAndCannotRunDuringRefresh() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        service.memberPage = { error("unavailable") }
        model.loadMore()
        advanceUntilIdle()
        assertEquals(1, model.state.value.members.page)
        assertTrue(model.state.value.members.errorIsPagination)
        service.memberPage = { query -> pending.await(); RolePlayMemberPage(listOf(member(7)), query.page, true, "new-snapshot") }
        model.refresh(); model.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2, 1), service.memberQueries.map { it.page })
        pending.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.members.errorIsPagination)
        assertEquals(listOf(7), model.state.value.members.items.map { it.id })
        service.memberPage = { RolePlayMemberPage(emptyList(), it.page, false, null) }
        model.loadMore()
        advanceUntilIdle()
        assertEquals("new-snapshot", service.memberQueries.last().pageTime)
    }

    @Test fun filteredOrDuplicateOnlyMemberPagesStillContinueWhenTheRawPageHasMore() = runTest {
        for (duplicate in listOf(false, true)) {
            val service = DirectoryFixture()
            service.memberPage = { query -> RolePlayMemberPage(
                when (query.page) {
                    1 -> listOf(member(1), member(1))
                    2 -> if (duplicate) listOf(member(1), member(1)) else emptyList()
                    else -> listOf(member(3))
                }, query.page, query.page < 3, "snapshot",
            ) }
            val model = RolePlayDirectoryViewModel(service)
            model.open(10, RolePlayDirectorySection.Members)
            advanceUntilIdle()
            model.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(1), model.state.value.members.items.map { it.id })
            assertTrue(model.state.value.members.hasMore)
            assertEquals(2, model.state.value.members.page)
            model.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(1, 3), model.state.value.members.items.map { it.id })
            assertFalse(model.state.value.members.hasMore)
            assertEquals(listOf(1, 2, 3), service.memberQueries.map { it.page })
        }
    }

    @Test fun aNonAdvancingReturnedPageStopsFurtherPagination() = runTest {
        val service = DirectoryFixture()
        service.memberPage = { RolePlayMemberPage(listOf(member(1)), 1, true, "snapshot") }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        assertFalse(model.state.value.members.hasMore)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), service.memberQueries.map { it.page })
    }

    @Test fun sectionSwitchingAndClosingPreserveIndependentListsAndSelections() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectMember(1)
        advanceUntilIdle()
        model.selectSection(RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.selectActivity(2)
        advanceUntilIdle()
        model.close()
        assertFalse(model.state.value.isOpen)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertEquals(1, model.state.value.members.selectedId)
        assertEquals(1, model.state.value.members.detail?.id)
        assertEquals(2, model.state.value.activities.selectedId)
        assertEquals(2, model.state.value.activities.detail?.activity?.id)
        assertEquals(1, service.memberQueries.size)
        assertEquals(1, service.activityQueries.size)
        assertEquals(1, service.memberDetailQueries.size)
        assertEquals(1, service.activityDetailQueries.size)
    }

    @Test fun clearSelectionOnlyClearsTheCurrentDirectoryAndReturningDoesNotReloadItsList() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectMember(1)
        advanceUntilIdle()
        model.selectSection(RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.selectActivity(2)
        advanceUntilIdle()
        model.clearSelection()
        assertNull(model.state.value.activities.selectedId)
        assertNull(model.state.value.activities.detail)
        assertEquals(1, model.state.value.members.selectedId)
        assertEquals(listOf(2, 1), model.state.value.activities.items.map { it.id })
        assertEquals(1, service.activityQueries.size)
    }

    @Test fun closingDuringAReadCachesItsResultWithoutReopeningTheDirectory() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = DirectoryFixture()
        service.memberPage = { query -> pending.await(); RolePlayMemberPage(listOf(member(1)), query.page, false, null) }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        runCurrent()
        model.close()
        pending.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isOpen)
        assertEquals(1, model.state.value.members.items.size)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertEquals(1, service.memberQueries.size)
    }

    @Test fun parentSwitchClearsBothDirectoriesAndRejectsANonCooperativeOldResponse() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = DirectoryFixture()
        service.memberPage = { query ->
            if (query.recruitmentId == 10) withContext(NonCancellable) { pending.await() }
            RolePlayMemberPage(listOf(member(query.recruitmentId)), query.page, false, "snapshot-${query.recruitmentId}")
        }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        runCurrent()
        model.setParent(20)
        assertFalse(model.state.value.isOpen)
        assertTrue(model.state.value.members.items.isEmpty())
        assertTrue(model.state.value.activities.items.isEmpty())
        model.open(20, RolePlayDirectorySection.Members)
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(20, model.state.value.parentId)
        assertEquals(listOf(20), model.state.value.members.items.map { it.id })
        assertFalse(model.state.value.members.isLoading)
    }

    @Test fun anOldRefreshFailureCannotPoisonANewerSuccessfulRefresh() = runTest {
        val pending = CompletableDeferred<Unit>()
        var reads = 0
        val service = DirectoryFixture()
        service.memberPage = { query ->
            when (++reads) {
                2 -> { withContext(NonCancellable) { pending.await() }; error("old failure") }
                else -> RolePlayMemberPage(listOf(member(reads)), query.page, false, null)
            }
        }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.refresh()
        runCurrent()
        model.refresh()
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(3), model.state.value.members.items.map { it.id })
        assertNull(model.state.value.members.error)
        assertFalse(model.state.value.members.isRefreshing)
    }

    @Test fun cancellationClearsPendingWithoutBecomingAnEmptySuccessfulResult() = runTest {
        val service = DirectoryFixture()
        service.memberPage = { throw CancellationException() }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertFalse(model.state.value.members.isLoading)
        assertFalse(model.state.value.members.hasLoaded)
        assertNull(model.state.value.members.error)
        service.memberPage = { RolePlayMemberPage(emptyList(), 1, false, null) }
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertTrue(model.state.value.members.hasLoaded)
        assertTrue(model.state.value.members.items.isEmpty())
    }

    @Test fun confirmedEmptyResultsDoNotAutomaticallyReloadOnReopening() = runTest {
        val service = DirectoryFixture()
        service.memberPage = { RolePlayMemberPage(emptyList(), 1, false, null) }
        service.activities = { emptyList() }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.close(); model.open(10, RolePlayDirectorySection.Members)
        model.selectSection(RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.close(); model.open(10, RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        assertEquals(1, service.memberQueries.size)
        assertEquals(1, service.activityQueries.size)
        assertTrue(model.state.value.activities.hasLoaded)
    }

    @Test fun initialFailureStaysVisibleUntilExplicitRefreshAndDoesNotLoopOnOpen() = runTest {
        val service = DirectoryFixture()
        service.activities = { error("private payload must not surface") }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.open(10, RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        assertEquals(1, service.activityQueries.size)
        assertEquals(RecruitmentInteractionError.Failed, model.state.value.activities.error)
        service.activities = { listOf(activity(2), activity(1), activity(2)) }
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(2, 1), model.state.value.activities.items.map { it.id })
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, service.activityQueries.size)
    }

    @Test fun failedActivityRefreshKeepsItsOriginalOrderAndSelectedDetail() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.selectActivity(2)
        advanceUntilIdle()
        service.activities = { error("unavailable") }
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(2, 1), model.state.value.activities.items.map { it.id })
        assertEquals(2, model.state.value.activities.selectedId)
        assertEquals(2, model.state.value.activities.detail?.activity?.id)
        assertFalse(model.state.value.activities.isRefreshing)
    }

    @Test fun detailsRejectExplicitlyDifferentParentsAndAcceptMissingParentMetadata() = runTest {
        val service = DirectoryFixture()
        service.memberDetail = { memberDetail(it, parent = 99) }
        service.activityDetail = { activityDetail(it, parent = 99) }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectMember(1)
        advanceUntilIdle()
        assertNull(model.state.value.members.detail)
        assertEquals(RecruitmentInteractionError.InvalidInput, model.state.value.members.detailError)
        service.memberDetail = { memberDetail(it, parent = null) }
        model.retryDetail()
        advanceUntilIdle()
        assertEquals(1, model.state.value.members.detail?.id)
        model.selectSection(RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.selectActivity(2)
        advanceUntilIdle()
        assertNull(model.state.value.activities.detail)
        assertEquals(RecruitmentInteractionError.InvalidInput, model.state.value.activities.detailError)
        service.activityDetail = { activityDetail(it, parent = null) }
        model.retryDetail()
        advanceUntilIdle()
        assertEquals(2, model.state.value.activities.detail?.activity?.id)
    }

    @Test fun unknownSelectionCannotIssueDetailRequestsAndWrongReturnedIdsAreRejected() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectMember(99); model.selectActivity(99)
        advanceUntilIdle()
        assertTrue(service.memberDetailQueries.isEmpty())
        assertTrue(service.activityDetailQueries.isEmpty())
        service.memberDetail = { memberDetail(99) }
        model.selectMember(1)
        advanceUntilIdle()
        assertNull(model.state.value.members.detail)
        assertEquals(RecruitmentInteractionError.InvalidInput, model.state.value.members.detailError)
    }

    @Test fun lateMemberDetailDoesNotOverwriteTheNextMemberSelection() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = DirectoryFixture()
        service.memberDetail = { id ->
            if (id == 1) withContext(NonCancellable) { pending.await() }
            memberDetail(id)
        }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectMember(1)
        runCurrent()
        model.selectMember(2)
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.members.selectedId)
        assertEquals(2, model.state.value.members.detail?.id)
        assertFalse(model.state.value.members.isLoadingDetail)
    }

    @Test fun failedOrCancelledDetailRetryRetainsTheConfirmedDetailAndDoesNotReloadTheList() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        model.selectActivity(2)
        advanceUntilIdle()
        val old = model.state.value.activities.detail
        assertEquals(2, old?.activity?.id)
        service.activityDetail = { error("unavailable") }
        model.retryDetail()
        advanceUntilIdle()
        assertEquals(old, model.state.value.activities.detail)
        assertEquals(RecruitmentInteractionError.Failed, model.state.value.activities.detailError)
        service.activityDetail = { throw CancellationException() }
        model.retryDetail()
        advanceUntilIdle()
        assertEquals(old, model.state.value.activities.detail)
        assertNull(model.state.value.activities.detailError)
        assertFalse(model.state.value.activities.isLoadingDetail)
        assertEquals(1, service.activityQueries.size)
    }

    @Test fun authenticationFailureClearsBothSectionsAndDiscardsOtherInFlightReads() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = DirectoryFixture()
        service.activities = { withContext(NonCancellable) { pending.await() }; listOf(activity(2)) }
        service.memberDetail = { throw DutyRecruitmentException.AuthenticationRequired }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectSection(RolePlayDirectorySection.Activities)
        runCurrent()
        model.selectSection(RolePlayDirectorySection.Members)
        model.selectMember(1)
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(10, model.state.value.parentId)
        assertTrue(model.state.value.isOpen)
        assertEquals(RolePlayDirectorySection.Members, model.state.value.section)
        assertTrue(model.state.value.members.items.isEmpty())
        assertTrue(model.state.value.activities.items.isEmpty())
        assertNull(model.state.value.members.selectedId)
        assertNull(model.state.value.members.detail)
        assertEquals(RecruitmentInteractionError.AuthenticationRequired, model.state.value.members.error)
    }

    @Test fun initialMemberAuthenticationFailureKeepsAnErrorVisibleWithoutAutomaticallyRetrying() = runTest {
        val service = DirectoryFixture()
        service.memberPage = { throw DutyRecruitmentException.AuthenticationRequired }
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertTrue(model.state.value.isOpen)
        assertEquals(10, model.state.value.parentId)
        assertEquals(RolePlayDirectorySection.Members, model.state.value.section)
        assertEquals(RecruitmentInteractionError.AuthenticationRequired, model.state.value.members.error)
        assertFalse(model.state.value.members.isLoading)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        assertEquals(1, service.memberQueries.size)
        model.clearProtectedContent()
        assertEquals(RolePlayDirectoryState(), model.state.value)
    }

    @Test fun initialActivityAuthenticationFailureKeepsNavigationAndClearsTheOtherDirectory() = runTest {
        val service = DirectoryFixture()
        val model = RolePlayDirectoryViewModel(service)
        model.open(10, RolePlayDirectorySection.Members)
        advanceUntilIdle()
        model.selectMember(1)
        advanceUntilIdle()
        service.activities = { throw DutyRecruitmentException.AuthenticationRequired }
        model.selectSection(RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        assertTrue(model.state.value.isOpen)
        assertEquals(10, model.state.value.parentId)
        assertEquals(RolePlayDirectorySection.Activities, model.state.value.section)
        assertEquals(RecruitmentInteractionError.AuthenticationRequired, model.state.value.activities.error)
        assertFalse(model.state.value.activities.isLoading)
        assertTrue(model.state.value.members.items.isEmpty())
        assertNull(model.state.value.members.selectedId)
        assertNull(model.state.value.members.detail)
        model.open(10, RolePlayDirectorySection.Activities)
        advanceUntilIdle()
        assertEquals(1, service.activityQueries.size)
    }

    @Test fun clearingTheScopePreventsOldReferencesFromOpeningOrReceivingLateResults() = runTest {
        val pending = CompletableDeferred<Unit>()
        val service = DirectoryFixture()
        service.memberPage = { query -> withContext(NonCancellable) { pending.await() }; RolePlayMemberPage(listOf(member(1)), query.page, false, null) }
        val store = ViewModelStore()
        val model = RolePlayDirectoryViewModel(service)
        store.put("directory", model)
        model.open(10, RolePlayDirectorySection.Members)
        runCurrent()
        store.clear()
        model.open(20, RolePlayDirectorySection.Activities)
        pending.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.supportsDirectory)
        assertEquals(RolePlayDirectoryState(), model.state.value)
        assertTrue(service.activityQueries.isEmpty())
    }
}

private class DirectoryFixture(val base: FakeDutyRecruitmentService = FakeDutyRecruitmentService()) :
    RolePlayDirectoryService, DutyRecruitmentService by base {
    val memberQueries = mutableListOf<RolePlayMemberQuery>()
    val memberDetailQueries = mutableListOf<Int>()
    val activityQueries = mutableListOf<Int>()
    val activityDetailQueries = mutableListOf<Int>()
    var memberPage: suspend (RolePlayMemberQuery) -> RolePlayMemberPage = { query ->
        RolePlayMemberPage(when (query.page) {
            1 -> listOf(member(1), member(2), member(2))
            2 -> listOf(member(2), member(3), member(3))
            else -> emptyList()
        }, query.page, query.page < 3, if (query.page == 1) "first-snapshot" else "do-not-adopt")
    }
    var memberDetail: suspend (Int) -> RolePlayMemberDetail = { memberDetail(it, parent = null) }
    var activities: suspend (Int) -> List<RolePlayActivity> = { listOf(activity(2), activity(1)) }
    var activityDetail: suspend (Int) -> RolePlayActivityDetail = { activityDetail(it, parent = null) }
    override suspend fun fetchRolePlayMemberPage(query: RolePlayMemberQuery): RolePlayMemberPage {
        memberQueries += query
        return memberPage(query)
    }
    override suspend fun fetchRolePlayMemberDetail(id: Int): RolePlayMemberDetail {
        memberDetailQueries += id
        return memberDetail(id)
    }
    override suspend fun fetchRolePlayActivities(recruitmentId: Int): List<RolePlayActivity> {
        activityQueries += recruitmentId
        return activities(recruitmentId)
    }
    override suspend fun fetchRolePlayActivityDetail(id: Int): RolePlayActivityDetail {
        activityDetailQueries += id
        return activityDetail(id)
    }
}

private fun member(id: Int) = RolePlayRecruitmentMember(id, "Member $id", null, null, "Description", emptyList())
private fun memberDetail(id: Int, parent: Int? = null) = RolePlayMemberDetail(id, parent, "Member $id", null, null, "Description", null)
private fun activity(id: Int, parent: Int? = null) = RolePlayActivity(id, parent, "Activity $id", null, "raw date", null, 1, 0)
private fun activityDetail(id: Int, parent: Int? = null) = RolePlayActivityDetail(activity(id, parent), "<p>Body</p>", emptyList(), false)
