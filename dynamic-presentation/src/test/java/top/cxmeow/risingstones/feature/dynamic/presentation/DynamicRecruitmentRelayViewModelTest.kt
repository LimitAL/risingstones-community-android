package top.cxmeow.risingstones.feature.dynamic.presentation

import androidx.lifecycle.ViewModelStore
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
import top.cxmeow.risingstones.feature.dynamic.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicRecruitmentRelayViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun openingAndChoosingVisibilityNeverWritesUntilExplicitSubmit() = runTest {
        for (origin in DynamicOrigin.entries.filter { it.wireValue in 5..9 }) {
            val service = RelayFixture()
            val model = DynamicRecruitmentRelayViewModel(service, service, 17, origin, "Source")
            model.initialize()
            model.setVisibility(DynamicVisibility.OnlyMe)
            advanceUntilIdle()
            assertEquals(1, service.scopes.size)
            assertTrue(service.calls.isEmpty())
            model.submit()
            advanceUntilIdle()
            assertEquals(DynamicRecruitmentRelayDraft(17, origin, DynamicVisibility.OnlyMe), service.calls.single())
        }
    }

    @Test fun duplicateClicksAndReentryDoNotDuplicateARequestOrCompletion() = runTest {
        val service = RelayFixture().apply { gate = CompletableDeferred() }
        val model = model(service)
        model.initialize()
        model.submit()
        model.submit()
        runCurrent()
        model.initialize()
        assertEquals(1, service.calls.size)
        assertEquals(1, service.scopes.size)
        assertFalse(model.requestClose())
        service.gate?.complete(Unit)
        advanceUntilIdle()
        val id = requireNotNull(model.state.value.completedId)
        assertTrue(model.takePublishedResult(id))
        assertFalse(model.takePublishedResult(id))
        model.submit()
        advanceUntilIdle()
        assertEquals(1, service.calls.size)
        assertTrue(service.scopes.single().closed)
    }

    @Test fun failureRetainsChoiceAndSourceForExplicitRetry() = runTest {
        val service = RelayFixture().apply { fail = true }
        val model = model(service)
        model.setVisibility(DynamicVisibility.MutualFollowers)
        model.submit()
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmitting)
        assertEquals(DynamicVisibility.MutualFollowers, model.state.value.visibility)
        assertEquals("Source", model.state.value.sourceTitle)
        assertEquals(1, service.calls.size)
        service.fail = false
        model.submit()
        advanceUntilIdle()
        assertEquals(2, service.calls.size)
        assertNotNull(model.state.value.completedId)
    }

    @Test fun scopeCreationFailureReleasesSubmissionWithoutClearingChoice() = runTest {
        val service = RelayFixture().apply { failScope = true }
        val model = model(service)
        model.setVisibility(DynamicVisibility.OnlyMe)
        model.submit()
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmitting)
        assertEquals(DynamicPublishingError.Failed, model.state.value.error)
        service.failScope = false
        model.submit()
        advanceUntilIdle()
        assertEquals(DynamicVisibility.OnlyMe, service.calls.single().visibility)
    }

    @Test fun revokedScopeClearsSourceEvenWhenCurrentAccountRemainsEligible() = runTest {
        val service = RelayFixture()
        val model = model(service)
        model.initialize()
        advanceUntilIdle()
        service.scopes.single().closed = true
        model.submit()
        advanceUntilIdle()
        assertTrue(service.canAttemptAuthenticatedWrites)
        assertNull(model.state.value.sourceTitle)
        assertFalse(model.state.value.usable)
        assertEquals(DynamicPublishingError.AuthenticationRequired, model.state.value.error)
        assertTrue(service.calls.isEmpty())
    }

    @Test fun clearingStoreIgnoresLateSuccessfulRelayWithoutReplaying() = runTest {
        val service = RelayFixture().apply { gate = CompletableDeferred() }
        val model = model(service)
        val store = ViewModelStore().apply { put("relay", model) }
        model.submit()
        runCurrent()
        store.clear()
        service.gate?.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.sourceTitle)
        assertNull(model.state.value.completedId)
        assertFalse(model.state.value.usable)
        assertTrue(service.scopes.single().closed)
        assertEquals(1, service.calls.size)
    }

    @Test fun backOrAccessLossPermanentlyDiscardsTheConfirmation() = runTest {
        for (close in listOf(true, false)) {
            val service = RelayFixture()
            val model = model(service)
            model.initialize()
            advanceUntilIdle()
            if (close) assertTrue(model.requestClose()) else {
                service.canAttemptAuthenticatedWrites = false
                model.synchronizeAccess()
                service.canAttemptAuthenticatedWrites = true
            }
            model.setVisibility(DynamicVisibility.OnlyMe)
            model.submit()
            advanceUntilIdle()
            assertNull(model.state.value.sourceTitle)
            assertTrue(service.calls.isEmpty())
            assertTrue(service.scopes.single().closed)
        }
    }

    private fun model(service: RelayFixture) = DynamicRecruitmentRelayViewModel(
        service, service, 17, DynamicOrigin.DutyRecruitment, "Source",
    )
}

private class RelayScope : DynamicActionScope {
    var closed = false
    override suspend fun isCurrent() = !closed
    override fun close() { closed = true }
}
private class RelayFixture : DynamicActionService, DynamicRecruitmentRelayService {
    override val canPerformAuthenticatedWrites = false
    override var canAttemptAuthenticatedWrites = true
    var fail = false
    var failScope = false
    var gate: CompletableDeferred<Unit>? = null
    val scopes = mutableListOf<RelayScope>()
    val calls = mutableListOf<DynamicRecruitmentRelayDraft>()
    override suspend fun beginActionScope(): DynamicActionScope {
        if (failScope) throw DynamicException.Network
        return RelayScope().also(scopes::add)
    }
    override suspend fun relayRecruitment(scope: DynamicActionScope, draft: DynamicRecruitmentRelayDraft) {
        calls += draft
        if (fail) throw DynamicException.Network
        withContext(NonCancellable) { gate?.await() }
    }
    override suspend fun entryEligibility(scope: DynamicActionScope, dynamicId: Int) = error("unused")
    override suspend fun commentEligibility(scope: DynamicActionScope, commentId: Int) = error("unused")
    override suspend fun fetchMentionCandidates(scope: DynamicActionScope, query: DynamicListQuery) = error("unused")
    override suspend fun fetchComments(scope: DynamicActionScope, dynamicId: Int, query: DynamicListQuery) = error("unused")
    override suspend fun fetchReplies(scope: DynamicActionScope, rootParentId: Int, query: DynamicListQuery) = error("unused")
    override suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int) = error("unused")
    override suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft) = error("unused")
    override suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int) = error("unused")
    override suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int) = error("unused")
}
