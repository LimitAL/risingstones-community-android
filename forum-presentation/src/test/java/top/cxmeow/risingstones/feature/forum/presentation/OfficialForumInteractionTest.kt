package top.cxmeow.risingstones.feature.forum.presentation

import java.time.Instant
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
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
import top.cxmeow.risingstones.feature.forum.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class OfficialForumInteractionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun clearingViewModelStoreReleasesDraftAndLateWriteCannotRestoreIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply { submit = { withContext(NonCancellable) { gate.await() }; listOf(10) } }
        val model = OfficialForumDetailViewModel(service, 42)
        val store = ViewModelStore().apply { put("detail", model) }
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("private text")
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        runCurrent()
        store.clear()
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        model.updateCommentDraft("stale composition")
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertFalse(model.state.value.isSubmittingComment)
    }

    @Test fun clearDuringInitialLoadResetsLoadingAndAllowsPublicReload() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply { detailRead = { withContext(NonCancellable) { gate.await() }; DETAIL } }
        val model = OfficialForumDetailViewModel(service, 42)
        runCurrent()
        model.clearProtectedContent()
        assertEquals(OfficialForumLoadStatus.Idle, model.state.value.status)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.detail)
        service.detailRead = null
        model.load()
        advanceUntilIdle()
        assertEquals(DETAIL.id, model.state.value.detail?.id)
        assertEquals(OfficialForumLoadStatus.Loaded, model.state.value.status)
    }

    @Test fun replyRefreshReloadsOpenRootInsteadOfLeavingOnlyPreview() = runTest {
        val service = InteractionService().apply {
            comments = { OfficialForumPage(listOf(ROOT.copy(childCount = 4)), 1, 1) }
            children = { query -> OfficialForumPage(if (query.limit == 3) CHILDREN.take(3) else CHILDREN, 4, 1) }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openSubComments(ROOT)
        advanceUntilIdle()
        val extra = ROOT.copy(id = 100, isMine = true)
        service.comments = { OfficialForumPage(listOf(ROOT.copy(childCount = 5)), 1, 1) }
        service.children = { query -> OfficialForumPage(if (query.limit == 3) CHILDREN.take(3) else CHILDREN + extra, 5, 1) }
        model.submitComment(OfficialForumReplyTarget(91, 9), "reply")
        advanceUntilIdle()
        assertEquals(91, service.submittedComments.single().parentId)
        assertEquals(9, service.submittedComments.single().rootParentId)
        assertEquals(9, model.state.value.selectedSubCommentRootId)
        assertEquals(listOf(91, 92, 93, 94, 100), model.state.value.subCommentsByRootId[9]?.map { it.id })
    }

    @Test fun failedVoteRetainsSelectionAndCancellationReleasesPending() = runTest {
        val service = InteractionService().apply { vote = { error("offline") } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.setVoteSelection(VOTE.id, setOf(2))
        model.submitSelectedVote(VOTE.id)
        advanceUntilIdle()
        assertEquals(setOf(2), model.interactionState.value.voteSelections[VOTE.id])
        assertEquals(OfficialForumInteractionError.Failed, model.interactionState.value.voteErrors[VOTE.id])
        service.vote = { throw CancellationException() }
        model.submitSelectedVote(VOTE.id)
        advanceUntilIdle()
        assertTrue(model.state.value.submittingVoteIds.isEmpty())
        assertNull(model.interactionState.value.voteErrors[VOTE.id])
        assertFalse(model.state.value.actionFailed)
        assertEquals(setOf(2), model.interactionState.value.voteSelections[VOTE.id])
    }

    @Test fun closingPreservesDraftAndDiscardReleasesImageAndReplyTarget() = runTest {
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer(OfficialForumReplyTarget(9, 9, "Fixture"))
        model.updateCommentDraft("kept draft")
        model.setCommentImage(IMAGE)
        model.closeCommentComposer()
        assertFalse(model.interactionState.value.isComposerOpen)
        assertEquals("kept draft", model.interactionState.value.commentText)
        assertEquals(9, model.interactionState.value.replyTarget?.rootParentId)
        assertArrayEquals(IMAGE.bytes, model.interactionState.value.commentImage?.bytes)
        model.openCommentComposer(requireNotNull(model.interactionState.value.replyTarget))
        assertEquals("kept draft", model.interactionState.value.commentText)
        model.discardCommentDraft()
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertTrue(service.submittedComments.isEmpty())
    }

    @Test fun unavailableReplyTargetCanBeResumedAndDiscardedButCannotBeSubmitted() = runTest {
        for (deleteTarget in listOf(false, true)) {
            val service = InteractionService()
            val model = OfficialForumDetailViewModel(service, 42)
            advanceUntilIdle()
            model.openCommentComposer(OfficialForumReplyTarget(ROOT.id, ROOT.id, "Fixture"))
            model.updateCommentDraft("kept reply")
            model.setCommentImage(IMAGE)
            model.closeCommentComposer()
            if (deleteTarget) model.deleteComment(ROOT)
            else {
                service.comments = { OfficialForumPage(emptyList(), 0, it.page) }
                model.toggleOnlyPostAuthor()
            }
            advanceUntilIdle()

            model.resumeCommentComposer()

            assertTrue(model.interactionState.value.isComposerOpen)
            assertEquals(ROOT.id, model.interactionState.value.replyTarget?.rootParentId)
            assertEquals("kept reply", model.interactionState.value.commentText)
            assertNotNull(model.interactionState.value.commentImage)
            assertEquals(OfficialForumInteractionError.InvalidInput, model.interactionState.value.commentError)
            model.submitDraftComment()
            advanceUntilIdle()
            assertTrue(service.submittedComments.isEmpty())
            assertEquals(0, service.uploads)
            model.discardCommentDraft()
            assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        }
    }

    @Test fun richDetailUsesOneReadAndPublicResultsPreventVotingWithoutPersonalParticipation() = runTest {
        val backing = InteractionService()
        var richReads = 0
        val service = object : OfficialForumInteractionService, OfficialForumImageUploadService by backing {
            override suspend fun fetchPostInteraction(id: Int): OfficialForumPostInteraction {
                richReads++
                return OfficialForumPostInteraction(DETAIL, setOf(VOTE.id))
            }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        assertEquals(1, richReads)
        assertEquals(0, backing.detailReads)
        assertEquals(setOf(VOTE.id), model.interactionState.value.voteResultsAvailable)
        assertFalse(requireNotNull(model.state.value.detail).votes.single().hasParticipated)
        model.setVoteSelection(VOTE.id, setOf(1))
        model.submitVote(VOTE, setOf(1))
        advanceUntilIdle()
        assertTrue(backing.submittedVotes.isEmpty())
        assertTrue(model.interactionState.value.voteSelections.isEmpty())
    }

    @Test fun deadlinePreventsStaleSelectionSubmissionWhileUnknownTextDoesNotInventDeadline() = runTest {
        val service = InteractionService().apply { detail = DETAIL.copy(votes = listOf(VOTE.copy(endDateText = "1000"))) }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitVote(VOTE, setOf(1))
        advanceUntilIdle()
        assertTrue(service.submittedVotes.isEmpty())
        service.detail = DETAIL.copy(votes = listOf(VOTE.copy(endDateText = "unparsed-date")))
        model.load()
        advanceUntilIdle()
        model.setVoteSelection(VOTE.id, setOf(1))
        model.submitSelectedVote(VOTE.id)
        advanceUntilIdle()
        assertEquals(1, service.submittedVotes.size)
    }

    @Test fun richerRefreshClearsSelectionWhenResultsBecomeAvailable() = runTest {
        val backing = InteractionService()
        var results = emptySet<String>()
        val service = object : OfficialForumInteractionService, OfficialForumImageUploadService by backing {
            override suspend fun fetchPostInteraction(id: Int) = OfficialForumPostInteraction(DETAIL, results)
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.setVoteSelection(VOTE.id, setOf(1))
        results = setOf(VOTE.id)
        model.load()
        advanceUntilIdle()
        assertTrue(model.interactionState.value.voteSelections.isEmpty())
        model.submitSelectedVote(VOTE.id)
        advanceUntilIdle()
        assertTrue(backing.submittedVotes.isEmpty())
    }

    @Test fun imageOnlyCommentUploadsAndSubmitsOnceWithSuccessRevision() = runTest {
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(1, service.uploads)
        assertEquals("", service.submittedComments.single().contentHtml)
        assertEquals("https://fixture.test/upload", service.submittedComments.single().commentPictureText)
        assertEquals(0, service.submittedComments.single().parentId)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
        assertFalse(model.interactionState.value.isComposerOpen)
        assertNull(model.interactionState.value.commentImage)
        assertFalse(model.state.value.isSubmittingComment)
    }

    @Test fun commentFailureRetainsDraftAndRetryReusesAlreadyUploadedImage() = runTest {
        val service = InteractionService().apply { submit = { error("offline") } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("draft")
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals("draft", model.interactionState.value.commentText)
        assertEquals(OfficialForumInteractionError.Failed, model.interactionState.value.commentError)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
        service.submit = { listOf(10) }
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(1, service.uploads)
        assertEquals(2, service.submittedComments.size)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
    }

    @Test fun pendingSubmissionFreezesTargetImageTextAndDismissal() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply { submit = { gate.await(); listOf(10) } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer(OfficialForumReplyTarget(9, 9))
        model.updateCommentDraft("original")
        model.submitDraftComment()
        runCurrent()
        model.updateCommentDraft("changed")
        model.setCommentImage(IMAGE)
        model.openCommentComposer()
        model.closeCommentComposer()
        model.discardCommentDraft()
        model.submitDraftComment()
        assertTrue(model.interactionState.value.isComposerOpen)
        assertEquals("original", model.interactionState.value.commentText)
        assertEquals(9, model.interactionState.value.replyTarget?.parentId)
        assertNull(model.interactionState.value.commentImage)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, service.submittedComments.size)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
    }

    @Test fun successfulWriteRemainsSuccessfulWhenRefreshAndLegacyCallbackFail() = runTest {
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        service.comments = { error("read failed") }
        var callbacks = 0
        model.submitComment(OfficialForumReplyTarget(0, 0), "committed") { callbacks++; error("old UI callback") }
        advanceUntilIdle()
        assertEquals(1, callbacks)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
        assertEquals("", model.interactionState.value.commentText)
        assertNull(model.interactionState.value.commentError)
        assertNull(model.interactionState.value.actionError)
        assertFalse(model.state.value.actionFailed)
        assertEquals(OfficialForumLoadStatus.Failed, model.state.value.commentsStatus)
        assertEquals(2, model.state.value.detail?.commentCount)
        assertEquals(listOf(9), model.state.value.comments.map { it.id })
        model.refreshComments()
        advanceUntilIdle()
        assertEquals(1, service.submittedComments.size)
    }

    @Test fun cancelledSubmissionKeepsDraftAndDoesNotBecomeFailureOrSuccess() = runTest {
        val service = InteractionService().apply { submit = { throw CancellationException() } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("keep")
        model.submitDraftComment()
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmittingComment)
        assertFalse(model.state.value.actionFailed)
        assertNull(model.interactionState.value.commentError)
        assertEquals("keep", model.interactionState.value.commentText)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
    }

    @Test fun lostWriteCapabilityPreventsEveryWriteAndClearsDraft() = runTest {
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("private draft")
        service.canPerformAuthenticatedWrites = false
        model.likePost()
        model.starPost()
        model.likeComment(ROOT)
        model.deleteComment(ROOT)
        model.submitComment(OfficialForumReplyTarget(0, 0), "text")
        model.submitVote(VOTE, setOf(1))
        advanceUntilIdle()
        assertEquals(0, service.mutations)
        assertTrue(service.submittedComments.isEmpty())
        assertTrue(service.submittedVotes.isEmpty())
        assertEquals("", model.interactionState.value.commentText)
        assertEquals(OfficialForumInteractionError.Unavailable, model.interactionState.value.actionError)
    }

    @Test fun uploadCapabilityIsIndependentAndLegacyServicesCannotSelectImage() = runTest {
        val service = InteractionService().apply { canUploadCommentImages = false }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.setCommentImage(IMAGE)
        model.submitComment(OfficialForumReplyTarget(0, 0), "text", IMAGE)
        advanceUntilIdle()
        assertEquals(0, service.uploads)
        assertTrue(service.submittedComments.isEmpty())
        assertEquals(OfficialForumInteractionError.Unavailable, model.interactionState.value.commentError)
        val legacy = object : OfficialForumService by service { }
        assertFalse(OfficialForumDetailViewModel(legacy, 42).canUploadCommentImages)
        advanceUntilIdle()
    }

    @Test fun authenticationFailureClearsIdentityAndIgnoresLateOtherWrite() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply {
            like = { withContext(NonCancellable) { gate.await() }; 1 }
            submit = { throw OfficialForumException.AuthenticationRequired }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("private draft")
        model.likePost()
        model.submitDraftComment()
        runCurrent()
        assertEquals("", model.interactionState.value.commentText)
        assertEquals(OfficialForumInteractionError.AuthenticationRequired, model.interactionState.value.commentError)
        assertEquals(DETAIL.bodyText, model.state.value.detail?.bodyText)
        assertFalse(model.state.value.comments.single().isMine)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.detail?.isLiked)
        assertFalse(model.state.value.isLikingPost)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
    }

    @Test fun explicitClearIgnoresLateSuccessfulCommentWithoutCallback() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply { submit = { withContext(NonCancellable) { gate.await() }; listOf(10) } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        var callbacks = 0
        model.submitComment(OfficialForumReplyTarget(0, 0), "draft") { callbacks++ }
        runCurrent()
        model.clearProtectedContent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, callbacks)
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertEquals(1, model.state.value.detail?.commentCount)
    }

    @Test fun staleVoteObjectCannotSubmitAgainAfterSuccessOrRefresh() = runTest {
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitVote(VOTE, setOf(1))
        model.submitVote(VOTE, setOf(1))
        advanceUntilIdle()
        model.load()
        advanceUntilIdle()
        model.submitVote(VOTE, setOf(2))
        advanceUntilIdle()
        assertEquals(1, service.submittedVotes.size)
        assertTrue(model.state.value.detail?.votes?.single()?.hasParticipated == true)
    }

    @Test fun voteValidatesLatestOptionsAndSingleSelectionRegardlessOfPictureDisplayType() = runTest {
        val service = InteractionService().apply { detail = DETAIL.copy(votes = listOf(VOTE.copy(type = 2, maximumSelectionCount = null))) }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitVote(VOTE.copy(id = "foreign"), setOf(1))
        model.submitVote(VOTE, setOf(1, 99))
        model.submitVote(VOTE, setOf(1, 2))
        advanceUntilIdle()
        assertTrue(service.submittedVotes.isEmpty())
        assertEquals(OfficialForumInteractionError.InvalidInput, model.interactionState.value.voteErrors[VOTE.id])
        model.setVoteSelection(VOTE.id, setOf(2))
        model.submitSelectedVote(VOTE.id)
        advanceUntilIdle()
        assertEquals(listOf(2), service.submittedVotes.single().options.map { it.optionId })
    }

    @Test fun multipleSelectionUsesOptionTypeAndMinimumMaximum() = runTest {
        val multi = VOTE.copy(type = 1, minimumSelectionCount = 2, maximumSelectionCount = 2,
            options = VOTE.options.map { it.copy(type = 2) })
        val service = InteractionService().apply { detail = DETAIL.copy(votes = listOf(multi)) }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitVote(multi, setOf(1))
        advanceUntilIdle()
        assertTrue(service.submittedVotes.isEmpty())
        model.setVoteSelection(multi.id, setOf(1, 2))
        model.submitSelectedVote(multi.id)
        advanceUntilIdle()
        assertEquals(2, service.submittedVotes.single().options.size)
    }

    @Test fun queryChangesReplacePendingRefreshAndDiscardOldQueryResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        service.comments = { query ->
            if (query.order == OfficialForumCommentOrder.Latest) withContext(NonCancellable) { gate.await() }
            OfficialForumPage(listOf(ROOT.copy(id = if (query.order == OfficialForumCommentOrder.Latest) 10 else 11)), 1, 1)
        }
        model.setCommentOrder(OfficialForumCommentOrder.Latest)
        runCurrent()
        model.setCommentOrder(OfficialForumCommentOrder.Earliest)
        runCurrent()
        assertEquals(listOf(11), model.state.value.comments.map { it.id })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OfficialForumCommentOrder.Earliest, model.state.value.commentOrder)
        assertEquals(listOf(11), model.state.value.comments.map { it.id })
    }

    @Test fun oldPaginationCannotAppendAfterRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply { comments = { OfficialForumPage(listOf(ROOT), 40, 1) } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        service.comments = { query ->
            if (query.page == 2) { withContext(NonCancellable) { gate.await() }; OfficialForumPage(listOf(ROOT.copy(id = 99)), 40, 2) }
            else OfficialForumPage(listOf(ROOT.copy(id = 10)), 1, 1)
        }
        model.loadMoreComments()
        runCurrent()
        model.refreshComments()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(10), model.state.value.comments.map { it.id })
        assertEquals(1, model.state.value.commentPage)
        assertFalse(model.state.value.isLoadingMoreComments)
    }

    @Test fun refreshFailurePreservesPaginationAndNextRequestUsesSuccessfulPage() = runTest {
        val service = InteractionService().apply {
            comments = { query -> OfficialForumPage((1..20).map { ROOT.copy(id = it + (query.page - 1) * 20) }, 100, query.page) }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.loadMoreComments()
        advanceUntilIdle()
        service.comments = { query -> if (query.page == 1) error("offline") else OfficialForumPage(emptyList(), 100, query.page) }
        model.refreshComments()
        advanceUntilIdle()
        assertEquals(2, model.state.value.commentPage)
        assertEquals(40, model.state.value.comments.size)
        model.loadMoreComments()
        advanceUntilIdle()
        assertEquals(3, service.commentQueries.last().page)
        assertEquals(40, model.state.value.comments.size)
        assertEquals(40, model.state.value.commentTotal)
    }

    @Test fun cancelledPaginationResetsPendingWithoutOrdinaryError() = runTest {
        val service = InteractionService().apply {
            comments = { query -> if (query.page > 1) throw CancellationException() else OfficialForumPage(listOf(ROOT), 40, 1) }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.loadMoreComments()
        advanceUntilIdle()
        assertFalse(model.state.value.isLoadingMoreComments)
        assertFalse(model.state.value.loadMoreFailed)
        assertEquals(1, model.state.value.commentPage)
    }

    @Test fun latePreviewCannotReplaceOpenedFullReplies() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService().apply {
            comments = { OfficialForumPage(listOf(ROOT.copy(childCount = 4)), 1, 1) }
            children = { query ->
                if (query.limit == 3) { gate.await(); OfficialForumPage(CHILDREN.take(1), 4, 1) }
                else OfficialForumPage(CHILDREN, 4, 1)
            }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        runCurrent()
        model.openSubComments(ROOT)
        runCurrent()
        assertEquals(4, model.state.value.subCommentsByRootId[9]?.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, model.state.value.subCommentsByRootId[9]?.size)
    }

    @Test fun childPaginationStopsOnEmptyOrRepeatedPageAndCancellationResetsPending() = runTest {
        val service = InteractionService().apply {
            comments = { OfficialForumPage(listOf(ROOT.copy(childCount = 100)), 1, 1) }
            children = { query -> OfficialForumPage(if (query.limit == 3 || query.page == 1) CHILDREN else emptyList(), 100, query.page) }
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openSubComments(ROOT)
        advanceUntilIdle()
        assertEquals(3, service.childQueries.size)
        assertEquals(4, model.state.value.subCommentsByRootId[9]?.size)
        service.children = { throw CancellationException() }
        model.openSubComments(ROOT)
        advanceUntilIdle()
        assertTrue(model.state.value.loadingSubCommentIds.isEmpty())
        assertTrue(model.state.value.subCommentErrorIds.isEmpty())
    }

    @Test fun lateDetailReadCannotUndoCommittedLike() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = InteractionService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        service.detailRead = { withContext(NonCancellable) { gate.await() }; DETAIL }
        model.load()
        runCurrent()
        model.likePost()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, model.state.value.detail?.isLiked)
        assertEquals(3, model.state.value.detail?.likeCount)
        assertEquals(OfficialForumLoadStatus.Loaded, model.state.value.status)
    }
}

private class InteractionService : OfficialForumImageUploadService {
    override var canPerformAuthenticatedWrites = true
    override var canUploadCommentImages = true
    var detail = DETAIL
    var detailRead: (suspend () -> OfficialForumPostDetail)? = null
    var comments: suspend (OfficialForumCommentQuery) -> OfficialForumPage<OfficialForumComment> = { OfficialForumPage(listOf(ROOT), 1, it.page) }
    var children: suspend (OfficialForumSubCommentQuery) -> OfficialForumPage<OfficialForumComment> = { OfficialForumPage(CHILDREN, 4, it.page) }
    var submit: suspend (OfficialForumCommentDraft) -> List<Int> = { listOf(10) }
    var like: suspend () -> Int = { 1 }
    var vote: suspend (OfficialForumVoteDraft) -> OfficialForumVoteResult = { OfficialForumVoteResult(5, mapOf(1 to 3, 2 to 2)) }
    var mutations = 0
    var detailReads = 0
    var uploads = 0
    val submittedComments = mutableListOf<OfficialForumCommentDraft>()
    val submittedVotes = mutableListOf<OfficialForumVoteDraft>()
    val commentQueries = mutableListOf<OfficialForumCommentQuery>()
    val childQueries = mutableListOf<OfficialForumSubCommentQuery>()
    override suspend fun fetchParts() = emptyList<OfficialForumPartFilter>()
    override suspend fun fetchPosts(query: OfficialForumListQuery) = OfficialForumPage<OfficialForumPostSummary>(emptyList(), 0, query.page)
    override suspend fun searchPosts(query: OfficialForumSearchQuery) = OfficialForumPage<OfficialForumPostSummary>(emptyList(), 0, query.page)
    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail { detailReads++; return detailRead?.invoke() ?: detail }
    override suspend fun fetchComments(query: OfficialForumCommentQuery): OfficialForumPage<OfficialForumComment> { commentQueries += query; return comments(query) }
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery): OfficialForumPage<OfficialForumComment> { childQueries += query; return children(query) }
    override suspend fun likePost(id: Int): Int { mutations++; return like() }
    override suspend fun starPost(id: Int): Int { mutations++; return 1 }
    override suspend fun likeComment(id: Int): Int { mutations++; return 1 }
    override suspend fun deleteComment(id: Int) { mutations++ }
    override suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload): String { uploads++; return "https://fixture.test/upload" }
    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> { submittedComments += draft; return submit(draft) }
    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult { submittedVotes += draft; return vote(draft) }
}

private val AUTHOR = OfficialForumAuthor("fixture", "Fixture", "World", "Area", null, 0)
private val ROOT = OfficialForumComment(9, AUTHOR, null, "Root", emptyList(), emptyList(), null, null, 0, false, 0, isPostAuthor = false, isMine = true)
private val CHILDREN = (1..4).map { ROOT.copy(id = 90 + it, isMine = false) }
private val VOTE = OfficialForumPostVote("vote", "Choice", 1, 1, 1, 0, null, 4,
    listOf(OfficialForumPostVoteOption("one", 1, "One", null, 1, 1, false), OfficialForumPostVoteOption("two", 2, "Two", null, 1, 3, false)))
private val DETAIL = OfficialForumPostDetail(42, "Fixture", "<p>Body</p>", "Body", emptyList(), emptyList(), emptyList(), listOf(VOTE),
    AUTHOR, OfficialForumPart(1, "Part", "Parent"), null, null, null, 1, 2, 3, false, false, 4, null, false, false)
private val IMAGE = OfficialForumCommentImageUpload(byteArrayOf(1, 2, 3), "image/png")
