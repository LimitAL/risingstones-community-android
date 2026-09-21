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
class RecruitmentInteractionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun latestListGenerationWinsEvenWhenTheCancelledServiceReturns() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage {
                val index = ++reads
                if (index == 1) withContext(NonCancellable) { old.await() }
                return DutyRecruitmentListPage(listOf(dutySummary(index)), 1, 1)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service))
        runCurrent()
        model.refresh()
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2), model.state.value.dutyItems.map { it.id })
        assertFalse(model.state.value.isLoading)
    }

    @Test fun changedFilterClearsOldContentAndAnOldFailureCannotPoisonIt() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage {
                if (++reads == 2) {
                    withContext(NonCancellable) { old.await() }
                    error("private old-query failure")
                }
                return DutyRecruitmentListPage(listOf(dutySummary(reads)), 1, 1)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        model.refresh()
        runCurrent()
        model.applyDutyFilter("High-end", "New", null)
        assertTrue(model.state.value.dutyItems.isEmpty())
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(3), model.state.value.dutyItems.map { it.id })
        assertNull(model.state.value.listError)
    }

    @Test fun failedRefreshRetainsSuccessfulPageAndBlocksPaginationUntilFinished() = runTest {
        val refresh = CompletableDeferred<Unit>()
        var failRefresh = false
        val pages = mutableListOf<Int>()
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage {
                pages += query.page
                if (failRefresh && query.page == 1) { refresh.await(); error("unavailable") }
                return DutyRecruitmentListPage(listOf(dutySummary(query.page)), 10, query.page)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        model.loadMore(); model.loadMore()
        advanceUntilIdle()
        assertEquals(2, model.state.value.page)
        failRefresh = true
        model.refresh(); model.loadMore()
        runCurrent()
        assertEquals(listOf(1, 2, 1), pages)
        refresh.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.page)
        assertEquals(listOf(1, 2), model.state.value.dutyItems.map { it.id })
        model.loadMore()
        advanceUntilIdle()
        assertEquals(3, pages.last())
    }

    @Test fun listDeduplicatesWithinPagesAndAnEmptyPageTerminatesPagination() = runTest {
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery) = DutyRecruitmentListPage(
                if (query.page == 1) listOf(dutySummary(1), dutySummary(1)) else emptyList(), 99, query.page,
            )
        }
        val model = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        assertEquals(1, model.state.value.dutyItems.size)
        model.loadMore()
        advanceUntilIdle()
        assertFalse(model.state.value.hasMore)
    }

    @Test fun listCancellationClearsPendingWithoutAnErrorAndAllowsRetry() = runTest {
        var cancel = true
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage {
                if (cancel) throw CancellationException("cancel")
                return base.fetchDutyRecruitments(query)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service))
        advanceUntilIdle()
        assertFalse(model.state.value.isLoading)
        assertNull(model.state.value.listError)
        cancel = false
        model.refresh()
        advanceUntilIdle()
        assertEquals(2, model.state.value.dutyItems.size)
    }

    @Test fun oldDetailCannotReplaceNewSelectionEvenWithTheSameIdAfterReturning() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail {
                val index = ++reads
                if (index == 1) withContext(NonCancellable) { old.await() }
                return base.fetchDutyRecruitmentDetail(id).copy(teamDetail = "read-$index")
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        runCurrent()
        model.selectDetail(2)
        model.selectDetail(1)
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals("read-2", model.state.value.dutyDetail?.teamDetail)
        assertFalse(model.state.value.isLoadingDetail)
    }

    @Test fun draftSurvivesCloseAndOversizedInputIsRejectedWithoutTruncation() = runTest {
        val base = FakeDutyRecruitmentService()
        val model = DutyRecruitmentViewModel(eligible(base), false)
        model.selectBoard(RecruitmentBoardKind.Beginner, false)
        model.selectDetail(11)
        advanceUntilIdle()
        model.openResponseComposer()
        val draft = "x".repeat(31)
        model.updateResponseDraft(draft)
        model.closeResponseComposer()
        model.openResponseComposer()
        assertEquals(draft, model.interactionState.value.contactDraft)
        model.submitResponseDraft()
        advanceUntilIdle()
        assertEquals(RecruitmentInteractionError.InvalidInput, model.interactionState.value.responseError)
        assertEquals(draft, model.interactionState.value.contactDraft)
        assertTrue(base.beginnerResponses.isEmpty())
        model.discardResponseDraft()
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.interactionState.value.isResponseComposerOpen)
    }

    @Test fun pendingResponseIsSingleAndSuccessWithoutReturnedContactNeverInventsOne() = runTest {
        val pending = CompletableDeferred<String?>()
        val submitted = mutableListOf<String>()
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? {
                submitted += contactInfo
                return pending.await()
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer()
        model.updateResponseDraft("  example contact  ")
        model.submitResponseDraft(); model.submitResponseDraft()
        model.updateResponseDraft("replacement")
        model.closeResponseComposer(); model.discardResponseDraft()
        assertTrue(model.state.value.isResponding)
        assertTrue(model.interactionState.value.isResponseComposerOpen)
        runCurrent()
        assertEquals(listOf("example contact"), submitted)
        pending.complete(null)
        advanceUntilIdle()
        assertTrue(model.state.value.dutyDetail?.isResponded == true)
        assertNull(model.state.value.responseContactInfo)
        assertEquals(1L, model.interactionState.value.responseSuccessRevision)
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.interactionState.value.isResponseComposerOpen)
        model.respond("again")
        advanceUntilIdle()
        assertEquals(1, submitted.size)
    }

    @Test fun responseFailureKeepsDraftAndAllowsOneExplicitRetry() = runTest {
        val base = FakeDutyRecruitmentService()
        val model = DutyRecruitmentViewModel(eligible(base), false)
        model.selectBoard(RecruitmentBoardKind.Beginner, false)
        model.selectDetail(11)
        advanceUntilIdle()
        model.openResponseComposer()
        model.updateResponseDraft("example")
        base.failBeginnerResponse = true
        model.submitResponseDraft()
        advanceUntilIdle()
        assertEquals(RecruitmentInteractionError.Failed, model.interactionState.value.responseError)
        assertEquals(0L, model.interactionState.value.responseSuccessRevision)
        assertEquals("example", model.interactionState.value.contactDraft)
        assertTrue(model.interactionState.value.isResponseComposerOpen)
        base.failBeginnerResponse = false
        model.submitResponseDraft()
        advanceUntilIdle()
        assertEquals(1L, model.interactionState.value.responseSuccessRevision)
    }

    @Test fun callbackFailureAndStaleRefreshCannotUndoAcknowledgedResponse() = runTest {
        val oldRefresh = CompletableDeferred<Unit>()
        var reads = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail {
                if (++reads == 2) oldRefresh.await()
                return base.fetchDutyRecruitmentDetail(id)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.refreshDetail()
        runCurrent()
        model.respond("example") { error("detached UI callback") }
        runCurrent()
        oldRefresh.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.dutyDetail?.isResponded == true)
        assertNull(model.state.value.responseError)
        assertEquals(1L, model.interactionState.value.responseSuccessRevision)
    }

    @Test fun lateResponseDoesNotUpdateOrCallbackForANewTarget() = runTest {
        val pending = CompletableDeferred<Unit>()
        var callbacks = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String {
                withContext(NonCancellable) { pending.await() }
                return "recruiter"
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.respond("example") { callbacks++ }
        runCurrent()
        model.selectDetail(2)
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.dutyDetail?.summary?.id)
        assertFalse(model.state.value.dutyDetail?.isResponded == true)
        assertFalse(model.state.value.isResponding)
        assertNull(model.state.value.responseContactInfo)
        assertEquals(0, callbacks)
        assertEquals(0L, model.interactionState.value.responseSuccessRevision)
    }

    @Test fun respondingRequiresWriteCapabilityLoadedDetailAndASupportedBoard() = runTest {
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val writable = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
            override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
        }
        val legacy = object : DutyRecruitmentService by writable { }
        val legacyModel = DutyRecruitmentViewModel(legacy, false)
        legacyModel.selectDetail(1)
        advanceUntilIdle()
        legacyModel.respond("example")
        assertFalse(legacyModel.canWrite)
        assertEquals(RecruitmentInteractionError.Unavailable, legacyModel.interactionState.value.responseError)
        val model = DutyRecruitmentViewModel(eligible(writable), false)
        model.selectDetail(1)
        model.respond("example")
        assertEquals(RecruitmentInteractionError.InvalidInput, model.interactionState.value.responseError)
        model.selectBoard(RecruitmentBoardKind.Other, false)
        model.selectDetail(11)
        advanceUntilIdle()
        model.respond("example")
        advanceUntilIdle()
        assertEquals(0, writes)
    }

    @Test fun firstWriteEligibilityEnablesExplicitResponseWithoutPretendingWriteWasVerified() = runTest {
        var eligibleForAttempt = true
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base, RecruitmentActionEligibilityService {
            override val canPerformAuthenticatedWrites = false
            override val canAttemptAuthenticatedWrites get() = eligibleForAttempt
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String {
                writes++
                return "Server contact"
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()

        assertFalse(model.canWrite)
        assertTrue(model.canAttemptWrite)
        assertTrue(model.canInteract)
        assertTrue(model.canRespond)
        model.openResponseComposer()
        model.updateResponseDraft("Fixture contact")
        model.submitResponseDraft()
        advanceUntilIdle()

        assertEquals(1, writes)
        assertEquals("Server contact", model.state.value.responseContactInfo)
        assertFalse(model.canWrite)
        eligibleForAttempt = false
        model.clearProtectedContent()
        assertFalse(model.canAttemptWrite)
        assertFalse(model.canInteract)
    }

    @Test fun capabilityRevokedBeforeQueuedWritePreventsTheRequestAndClearsDraft() = runTest {
        var writable = true
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override val canPerformAuthenticatedWrites get() = writable
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer()
        model.updateResponseDraft("example")
        model.submitResponseDraft()
        writable = false
        advanceUntilIdle()
        assertEquals(0, writes)
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.state.value.isResponding)
    }

    @Test fun authenticationFailureClearsPrivateContentAndPublicReadsRemainRetryable() = runTest {
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? =
                throw DutyRecruitmentException.AuthenticationRequired
        }
        val model = DutyRecruitmentViewModel(eligible(service))
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer()
        model.updateResponseDraft("example")
        model.submitResponseDraft()
        advanceUntilIdle()
        assertFalse(model.canWrite)
        assertNull(model.state.value.dutyDetail)
        assertFalse(model.state.value.isResponding)
        assertEquals("", model.interactionState.value.contactDraft)
        assertEquals(RecruitmentInteractionError.AuthenticationRequired, model.interactionState.value.responseError)
        assertEquals(2, model.state.value.dutyItems.size)
        model.retryDetail()
        advanceUntilIdle()
        assertEquals(1, model.state.value.dutyDetail?.summary?.id)
    }

    @Test fun responseCancellationRetainsDraftWithoutReportingFailure() = runTest {
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? = throw CancellationException()
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer(); model.updateResponseDraft("example"); model.submitResponseDraft()
        advanceUntilIdle()
        assertFalse(model.state.value.isResponding)
        assertNull(model.interactionState.value.responseError)
        assertEquals("example", model.interactionState.value.contactDraft)
        assertEquals(0L, model.interactionState.value.responseSuccessRevision)
    }

    @Test fun membersRetryIsLocalAndRetainsTheOtherConfirmedSections() = runTest {
        var membersReads = 0
        var ratingReads = 0
        var reviewReads = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchRolePlayMembers(id: Int): List<RolePlayRecruitmentMember> {
                if (++membersReads == 1) error("members unavailable")
                return base.fetchRolePlayMembers(id)
            }
            override suspend fun fetchRolePlayRating(id: Int): RolePlayRecruitmentRating { ratingReads++; return base.fetchRolePlayRating(id) }
            override suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int): RolePlayRecruitmentReviewPage {
                reviewReads++; return base.fetchRolePlayReviews(id, page, limit)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        assertEquals("Failed", model.state.value.membersError)
        assertEquals(1, model.state.value.reviews.size)
        model.refreshMembers()
        advanceUntilIdle()
        assertEquals(2, membersReads)
        assertEquals(1, ratingReads)
        assertEquals(1, reviewReads)
        assertEquals(1, model.state.value.members.size)
    }

    @Test fun repliesHaveIndependentPagesDeduplicateAndStopOnEmptyPages() = runTest {
        val pages = mutableListOf<Pair<Int, Int>>()
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage {
                pages += page to limit
                return RolePlayRecruitmentSubcommentPage(
                    when { limit == 3 -> listOf(reply("preview")); page == 1 -> listOf(reply("a"), reply("a"));
                        page == 2 -> listOf(reply("a"), reply("b"), reply("b")); else -> emptyList() }, page, true,
                )
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        val review = model.state.value.reviews.single()
        model.openReviewReplies(review); model.openReviewReplies(review)
        advanceUntilIdle()
        assertEquals("review-1", model.interactionState.value.selectedReviewId)
        assertEquals(listOf("a"), model.state.value.subcomments[review.id]?.map { it.id })
        model.loadMoreSubcomments(review.id); model.loadMoreSubcomments(review.id)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), model.state.value.subcomments[review.id]?.map { it.id })
        assertEquals(2, model.interactionState.value.subcommentPages[review.id])
        model.dismissReviewReplies(); model.openReviewReplies(review)
        assertEquals(3, pages.size)
        model.loadMoreSubcomments(review.id)
        advanceUntilIdle()
        assertFalse(review.id in model.interactionState.value.hasMoreSubcommentIds)
        assertEquals(listOf(1 to 3, 1 to 10, 2 to 10, 3 to 10), pages)
    }

    @Test fun replyRefreshFailureRetainsPageAndFullRepliesAcrossReviewRefresh() = runTest {
        var fail = false
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage {
                if (fail && limit == 10) error("unavailable")
                return RolePlayRecruitmentSubcommentPage(listOf(reply(if (limit == 3) "preview" else "page-$page")), page, true)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        val review = model.state.value.reviews.single()
        model.openReviewReplies(review)
        advanceUntilIdle()
        model.loadMoreSubcomments(review.id)
        advanceUntilIdle()
        fail = true
        model.loadSubcomments(review); model.refreshReviews()
        advanceUntilIdle()
        assertEquals(listOf("page-1", "page-2"), model.state.value.subcomments[review.id]?.map { it.id })
        assertEquals(2, model.interactionState.value.subcommentPages[review.id])
        assertEquals(RecruitmentInteractionError.Failed, model.interactionState.value.subcommentErrors[review.id])
    }

    @Test fun oldSubcommentFailureCannotPolluteANewDetail() = runTest {
        val pending = CompletableDeferred<Unit>()
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind) =
                base.fetchCommunityRecruitmentDetail(id, kind).let { it.copy(summary = it.summary.copy(id = id)) }
            override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage {
                if (limit == 10) { withContext(NonCancellable) { pending.await() }; error("old private failure") }
                return base.fetchRolePlaySubcomments(rootParentId, page, limit)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        model.openReviewReplies(model.state.value.reviews.single())
        runCurrent()
        model.selectDetail(15)
        runCurrent()
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(15, model.state.value.selectedId)
        assertTrue(model.interactionState.value.subcommentErrors.isEmpty())
        assertNull(model.interactionState.value.selectedReviewId)
        assertTrue(model.state.value.loadingSubcommentIds.isEmpty())
    }

    @Test fun cancelledPreviewDoesNotBecomeAnEmptySuccessfulReviewPage() = runTest {
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage =
                throw CancellationException()
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        assertEquals(0, model.state.value.reviewsPage)
        assertTrue(model.state.value.reviews.isEmpty())
        assertFalse(model.state.value.isLoadingReviews)
        assertNull(model.state.value.reviewsError)
    }

    @Test fun previewAuthenticationFailureClearsLoadedDetailAndInteractionState() = runTest {
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage =
                throw DutyRecruitmentException.AuthenticationRequired
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        assertFalse(model.canWrite)
        assertNull(model.state.value.communityDetail)
        assertFalse(model.state.value.isLoadingReviews)
        assertEquals("AuthenticationRequired", model.state.value.reviewsError)
    }

    @Test fun likingUsesCurrentReviewMembershipAndBlocksDoubleClick() = runTest {
        val pending = CompletableDeferred<Int>()
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun likeRolePlayReview(id: String): Int { writes++; return pending.await() }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        model.likeReview(review("foreign"))
        assertEquals(RecruitmentInteractionError.InvalidInput, model.interactionState.value.interactionError)
        val current = model.state.value.reviews.single()
        model.likeReview(current.copy(isLiked = true, likeCount = 99)); model.likeReview(current)
        runCurrent()
        assertEquals(1, writes)
        pending.complete(1)
        advanceUntilIdle()
        assertEquals(3, model.state.value.reviews.single().likeCount)
        model.refreshReviews()
        advanceUntilIdle()
        assertTrue(model.state.value.reviews.single().isLiked)
        assertEquals(3, model.state.value.reviews.single().likeCount)
    }

    @Test fun replyRetryDistinguishesFailedRefreshFromFailedPaginationUsingConfirmedPages() = runTest {
        var failingPage: Int? = null
        val requestedPages = mutableListOf<Int>()
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage {
                if (limit == 10) {
                    requestedPages += page
                    if (page == failingPage) error("unavailable")
                }
                return RolePlayRecruitmentSubcommentPage(listOf(reply("page-$page")), page, true)
            }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(RecruitmentBoardKind.RolePlay, false); model.selectDetail(14)
        advanceUntilIdle()
        val root = model.state.value.reviews.single()
        model.openReviewReplies(root)
        advanceUntilIdle()
        failingPage = 2
        model.loadMoreSubcomments(root.id)
        advanceUntilIdle()
        assertTrue(root.id in model.interactionState.value.subcommentErrorIsPaginationIds)
        assertEquals(1, model.interactionState.value.subcommentPages[root.id])
        failingPage = null
        model.loadMoreSubcomments(root.id)
        advanceUntilIdle()
        assertFalse(root.id in model.interactionState.value.subcommentErrorIsPaginationIds)
        assertEquals(2, model.interactionState.value.subcommentPages[root.id])
        failingPage = 1
        model.loadSubcomments(root)
        advanceUntilIdle()
        assertFalse(root.id in model.interactionState.value.subcommentErrorIsPaginationIds)
        assertEquals(2, model.interactionState.value.subcommentPages[root.id])
        assertEquals(listOf(1, 2, 2, 1), requestedPages)
    }

    @Test fun guildClearErasesPrivateListAndReadPendingButPublicBoardCanLoadAgain() = runTest {
        val base = FakeDutyRecruitmentService()
        val model = DutyRecruitmentViewModel(eligible(base), false)
        model.selectBoard(RecruitmentBoardKind.Guild)
        advanceUntilIdle()
        assertTrue(model.state.value.communityItems.isNotEmpty())
        model.clearProtectedContent()
        assertTrue(model.state.value.communityItems.isEmpty())
        assertFalse(model.hasCommunityIdentity)
        assertFalse(model.state.value.isLoading)
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, base.communityQueries.size)
        model.selectBoard(RecruitmentBoardKind.Duty)
        advanceUntilIdle()
        assertEquals(2, model.state.value.dutyItems.size)
    }

    @Test fun clearingAViewModelStoreErasesDraftAndPreventsAnOldReferenceFromReopeningIt() = runTest {
        val model = DutyRecruitmentViewModel(eligible(FakeDutyRecruitmentService()), false)
        val store = ViewModelStore()
        store.put("detail", model)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer(); model.updateResponseDraft("example")
        store.clear()
        model.openResponseComposer(); model.updateResponseDraft("old UI")
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.interactionState.value.isResponseComposerOpen)
        assertFalse(model.canWrite)
        assertNull(model.state.value.dutyDetail)
    }
}

private fun reply(id: String) = RolePlayRecruitmentSubcomment(id, "Synthetic", null, null, "Reply", emptyList())

internal fun eligible(service: DutyRecruitmentService, author: Boolean? = false): RecruitmentResponseEligibilityService =
    object : RecruitmentResponseEligibilityService, RecruitmentActionEligibilityService, DutyRecruitmentService by service {
        override val canPerformAuthenticatedWrites: Boolean
            get() = (service as? RecruitmentInteractionService)?.canPerformAuthenticatedWrites == true
        override val canAttemptAuthenticatedWrites: Boolean
            get() = (service as? RecruitmentActionEligibilityService)?.canAttemptAuthenticatedWrites == true
        override suspend fun fetchDutyInteractionDetail(id: Int) =
            DutyRecruitmentInteractionDetail(service.fetchDutyRecruitmentDetail(id), author)
        override suspend fun fetchCommunityInteractionDetail(id: Int, kind: CommunityRecruitmentKind) =
            CommunityRecruitmentInteractionDetail(service.fetchCommunityRecruitmentDetail(id, kind), author)
    }
