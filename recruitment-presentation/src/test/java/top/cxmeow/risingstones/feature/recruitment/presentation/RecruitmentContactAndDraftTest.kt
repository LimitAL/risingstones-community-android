package top.cxmeow.risingstones.feature.recruitment.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecruitmentContactAndDraftTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun dutyNullResponseDoesNotExposeOldContactAndAConfirmedRefreshProvidesTheNewContact() = runTest {
        verifyContactRefresh(RecruitmentBoardKind.Duty)
    }

    @Test fun beginnerNullResponseDoesNotEraseTheContactObtainedByAConfirmedRefresh() = runTest {
        verifyContactRefresh(RecruitmentBoardKind.Beginner)
    }

    private suspend fun TestScope.verifyContactRefresh(board: RecruitmentBoardKind) {
        var fetchedContact = "old detail contact"
        var failRead = false
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail {
                if (failRead) error("unavailable")
                return base.fetchDutyRecruitmentDetail(id).copy(contactInfo = fetchedContact)
            }
            override suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentDetail {
                if (failRead) error("unavailable")
                val result = base.fetchCommunityRecruitmentDetail(id, kind)
                return result.copy(summary = result.summary.copy(beginner = result.summary.beginner?.copy(recruiterContactInfo = fetchedContact)))
            }
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
            override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectBoard(board, false)
        model.selectDetail(if (board == RecruitmentBoardKind.Duty) 1 else 11)
        advanceUntilIdle()
        model.respond("my submitted contact")
        advanceUntilIdle()
        assertNull(model.state.value.responseContactInfo)
        assertFalse(model.interactionState.value.hasRefreshedResponseContact)
        failRead = true
        model.refreshDetail()
        advanceUntilIdle()
        assertFalse(model.interactionState.value.hasRefreshedResponseContact)
        assertNull(model.state.value.responseContactInfo)
        failRead = false
        fetchedContact = "new authoritative contact"
        model.refreshDetail()
        advanceUntilIdle()
        assertEquals(fetchedContact, model.state.value.responseContactInfo)
        assertTrue(model.interactionState.value.hasRefreshedResponseContact)
        val detailContact = model.state.value.dutyDetail?.contactInfo ?: model.state.value.communityDetail?.summary?.beginner?.recruiterContactInfo
        assertEquals(fetchedContact, detailContact)
        model.respond("do not repeat")
        advanceUntilIdle()
        assertEquals(1, writes)
        model.clearProtectedContent()
        assertNull(model.state.value.responseContactInfo)
        assertFalse(model.interactionState.value.hasRefreshedResponseContact)
    }

    @Test fun detailReadStartedBeforeSubmissionCannotAuthorizeAnOldContactAsFresh() = runTest {
        val oldRead = CompletableDeferred<Unit>()
        var reads = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail {
                if (++reads == 2) oldRead.await()
                return base.fetchDutyRecruitmentDetail(id).copy(contactInfo = "pre-submission detail contact")
            }
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? = null
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.refreshDetail()
        runCurrent()
        model.respond("my submitted contact")
        runCurrent()
        oldRead.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.dutyDetail?.isResponded == true)
        assertNull(model.state.value.responseContactInfo)
        assertFalse(model.interactionState.value.hasRefreshedResponseContact)
    }

    @Test fun unknownEligibilityStillAllowsRetainedDraftToBeViewedAndDiscardedButNotSubmitted() = runTest {
        var author: Boolean? = false
        var writes = 0
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentResponseEligibilityService by eligible(base) {
            override suspend fun fetchDutyInteractionDetail(id: Int) =
                DutyRecruitmentInteractionDetail(base.fetchDutyRecruitmentDetail(id), author)
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? { writes++; return null }
        }
        val model = DutyRecruitmentViewModel(service, false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer(); model.updateResponseDraft("retained contact"); model.closeResponseComposer()
        author = null
        model.refreshDetail()
        advanceUntilIdle()
        assertFalse(model.canRespond)
        model.openResponseComposer()
        assertFalse(model.interactionState.value.isResponseComposerOpen)
        model.resumeResponseComposer()
        assertTrue(model.interactionState.value.isResponseComposerOpen)
        assertEquals("retained contact", model.interactionState.value.contactDraft)
        assertEquals(RecruitmentInteractionError.InvalidInput, model.interactionState.value.responseError)
        model.submitResponseDraft()
        advanceUntilIdle()
        assertEquals(0, writes)
        model.discardResponseDraft()
        model.resumeResponseComposer()
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.interactionState.value.isResponseComposerOpen)
    }

    @Test fun resumingAfterWriteCapabilityIsWithdrawnErasesTheRetainedDraft() = runTest {
        var writable = true
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override val canPerformAuthenticatedWrites get() = writable
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer(); model.updateResponseDraft("retained contact"); model.closeResponseComposer()
        writable = false
        model.resumeResponseComposer()
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.interactionState.value.isResponseComposerOpen)
        assertFalse(model.canWrite)
    }

    @Test fun resumeCannotOpenOrChangeAPendingResponseAndRevocationClearsTheDraft() = runTest {
        val pending = CompletableDeferred<String?>()
        val base = FakeDutyRecruitmentService()
        val service = object : RecruitmentInteractionService by base {
            override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? = pending.await()
        }
        val model = DutyRecruitmentViewModel(eligible(service), false)
        model.selectDetail(1)
        advanceUntilIdle()
        model.openResponseComposer(); model.updateResponseDraft("retained contact"); model.closeResponseComposer()
        model.respond("retained contact")
        runCurrent()
        model.resumeResponseComposer()
        assertFalse(model.interactionState.value.isResponseComposerOpen)
        assertEquals("retained contact", model.interactionState.value.contactDraft)
        model.clearProtectedContent()
        model.resumeResponseComposer()
        assertEquals("", model.interactionState.value.contactDraft)
        assertFalse(model.interactionState.value.isResponseComposerOpen)
        assertFalse(model.state.value.isResponding)
    }
}
