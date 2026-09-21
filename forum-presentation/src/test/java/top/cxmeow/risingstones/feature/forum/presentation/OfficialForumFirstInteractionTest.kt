package top.cxmeow.risingstones.feature.forum.presentation

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
class OfficialForumFirstInteractionTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun firstEligibilityDoesNotClaimVerifiedCapabilitiesOrPerformWritesWhileEditing() = runTest {
        val service = EligibleForumService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        assertTrue(model.canInteract)
        assertTrue(model.canAttachCommentImage)
        assertFalse(model.canWrite)
        assertFalse(model.canUploadCommentImages)
        model.openCommentComposer()
        model.updateCommentDraft("Draft")
        model.insertCommentEmoji(1)
        model.setCommentImage(IMAGE)
        model.closeCommentComposer()
        model.resumeCommentComposer()
        assertEquals("Draft[emo1]", model.interactionState.value.commentText)
        assertNotNull(model.interactionState.value.commentImage)
        assertEquals(0, service.writes)
        assertEquals(0, service.uploads)
    }

    @Test fun eachExplicitActionCanBeTheFirstVerifiedWrite() = runTest {
        val actions: List<(OfficialForumDetailViewModel) -> Unit> = listOf(
            { it.likePost() }, { it.starPost() }, { it.likeComment(ROOT) }, { it.deleteComment(ROOT) },
            { it.submitComment(OfficialForumReplyTarget(0, 0), "First comment") },
            { it.submitVote(VOTE, setOf(1)) },
        )
        actions.forEach { action ->
            val service = EligibleForumService().apply { grantOnSuccess = true }
            val model = OfficialForumDetailViewModel(service, 42)
            advanceUntilIdle()
            assertFalse(model.canWrite)
            action(model)
            advanceUntilIdle()
            assertEquals(1, service.writes)
            assertTrue(model.canWrite)
            assertFalse(model.canUploadCommentImages)
            assertFalse(model.state.value.actionFailed)
        }
    }

    @Test fun viewModelNeverGrantsAServiceCapabilityOnItsOwn() = runTest {
        val service = EligibleForumService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.likePost()
        advanceUntilIdle()
        assertEquals(true, model.state.value.detail?.isLiked)
        assertFalse(model.canWrite)
        assertTrue(model.canInteract)
    }

    @Test fun anonymousAndOldUnverifiedServicesCannotEditOrWrite() = runTest {
        val source = EligibleForumService().apply { canAttemptAuthenticatedWrites = false }
        val services = listOf<OfficialForumService>(source, object : OfficialForumService by source {})
        services.forEach { service ->
            val model = OfficialForumDetailViewModel(service, 42)
            advanceUntilIdle()
            model.openCommentComposer()
            model.updateCommentDraft("Rejected")
            model.setCommentImage(IMAGE)
            model.setVoteSelection("vote", setOf(1))
            model.likePost()
            model.submitComment(OfficialForumReplyTarget(0, 0), "Rejected")
            advanceUntilIdle()
            assertFalse(model.canInteract)
            assertFalse(model.canAttachCommentImage)
            assertFalse(model.interactionState.value.isComposerOpen)
            assertEquals("", model.interactionState.value.commentText)
            assertNull(model.interactionState.value.commentImage)
            assertTrue(model.interactionState.value.voteSelections.isEmpty())
        }
        assertEquals(0, source.writes)
        assertEquals(0, source.uploads)
    }

    @Test fun legacyVerifiedServicesRemainSupportedWithoutEligibilityExtension() = runTest {
        val source = EligibleForumService().apply { canPerformAuthenticatedWrites = true }
        val legacy = object : OfficialForumService by source {}
        val model = OfficialForumDetailViewModel(legacy, 42)
        advanceUntilIdle()
        model.submitComment(OfficialForumReplyTarget(0, 0), "Legacy")
        advanceUntilIdle()
        assertTrue(model.canWrite)
        assertTrue(model.canInteract)
        assertFalse(model.canAttachCommentImage)
        assertEquals(1, source.writes)
    }

    @Test fun imageEligibilityIsIndependentAndImageOnlyEligibilityCannotSend() = runTest {
        val service = EligibleForumService().apply { canAttemptCommentImageUpload = false }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("Text allowed")
        model.setCommentImage(IMAGE)
        assertNull(model.interactionState.value.commentImage)
        assertTrue(model.canInteract)
        assertFalse(model.canAttachCommentImage)
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(1, service.writes)
        assertEquals(0, service.uploads)
        service.canAttemptAuthenticatedWrites = false
        service.canAttemptCommentImageUpload = true
        model.synchronizeActionEligibility()
        assertFalse(model.canAttachCommentImage)
        model.setCommentImage(IMAGE)
        model.submitComment(OfficialForumReplyTarget(0, 0), "", IMAGE)
        advanceUntilIdle()
        assertEquals(1, service.writes)
        assertEquals(0, service.uploads)
    }

    @Test fun imageUploadSuccessDoesNotMeanCommentSuccessAndRetryReusesUploadedImage() = runTest {
        val service = EligibleForumService().apply {
            grantOnSuccess = true
            nextWriteFailure = OfficialForumException.Business(12345, "fixture")
        }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("Preserved")
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        advanceUntilIdle()
        assertTrue(model.canUploadCommentImages)
        assertFalse(model.canWrite)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
        assertEquals("Preserved", model.interactionState.value.commentText)
        assertNotNull(model.interactionState.value.commentImage)
        assertEquals(OfficialForumInteractionError.Failed, model.interactionState.value.commentError)
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(1, service.uploads)
        assertEquals(2, service.writes)
        assertTrue(model.canWrite)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
        assertEquals(listOf("fixture-image", "fixture-image"), service.comments.map { it.commentPictureText })
    }

    @Test fun doubleTapWhilePendingSubmitsOnlyOnceAndKeepsDraftUntilSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = EligibleForumService().apply { writeGate = gate }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("Pending")
        model.submitDraftComment()
        model.submitDraftComment()
        runCurrent()
        assertEquals(1, service.writes)
        assertEquals("Pending", model.interactionState.value.commentText)
        assertTrue(model.state.value.isSubmittingComment)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, service.writes)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
        assertEquals("", model.interactionState.value.commentText)
    }

    @Test fun revokedEligibilityClearsDraftCandidatesSelectionsAndPendingWithoutLateRestore() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = EligibleForumService().apply { writeGate = gate; ignoreWriteCancellation = true }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("Private")
        model.openCommentMentionPicker()
        advanceUntilIdle()
        model.selectCommentMention(CANDIDATE)
        model.setCommentImage(IMAGE)
        model.setVoteSelection("vote", setOf(1))
        model.submitDraftComment()
        runCurrent()
        val revision = model.commentDraftRevision
        service.canAttemptAuthenticatedWrites = false
        model.synchronizeActionEligibility()
        assertTrue(model.commentDraftRevision > revision)
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertFalse(model.state.value.isSubmittingComment)
        service.canAttemptAuthenticatedWrites = true
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        model.submitComment(OfficialForumReplyTarget(0, 0), "New draft", IMAGE)
        advanceUntilIdle()
        assertEquals(2, service.uploads)
    }

    @Test fun losingOnlyImageEligibilityClearsCachedUploadAndCancelsPendingComment() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = EligibleForumService().apply { uploadGate = gate }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("Private")
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        runCurrent()
        service.canAttemptCommentImageUpload = false
        model.synchronizeActionEligibility()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.canInteract)
        assertEquals(0, service.writes)
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertFalse(model.state.value.isSubmittingComment)
    }

    @Test fun independentImageRevocationAfterUploadPreventsTheCommentEvenWithoutUiObserver() = runTest {
        val service = EligibleForumService().apply { afterUpload = { canAttemptCommentImageUpload = false } }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitComment(OfficialForumReplyTarget(0, 0), "Do not send", IMAGE)
        advanceUntilIdle()
        assertEquals(1, service.uploads)
        assertEquals(0, service.writes)
        assertNull(model.interactionState.value.commentImage)
        assertEquals("", model.interactionState.value.commentText)
        assertFalse(model.state.value.isSubmittingComment)
    }

    @Test fun authenticationFailureClearsPrivateDraftInsteadOfKeepingItForRetry() = runTest {
        val service = EligibleForumService().apply { nextWriteFailure = OfficialForumException.AuthenticationRequired }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitComment(OfficialForumReplyTarget(0, 0), "Private", IMAGE)
        advanceUntilIdle()
        assertEquals("", model.interactionState.value.commentText)
        assertNull(model.interactionState.value.commentImage)
        assertEquals(OfficialForumInteractionError.AuthenticationRequired, model.interactionState.value.commentError)
        assertEquals(1, service.writes)
    }

    @Test fun cancellationRetainsDraftAndNeverBecomesAnOrdinaryError() = runTest {
        val service = EligibleForumService().apply { nextWriteFailure = CancellationException("synthetic") }
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.submitComment(OfficialForumReplyTarget(0, 0), "Retry")
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmittingComment)
        assertFalse(model.state.value.actionFailed)
        assertNull(model.interactionState.value.commentError)
        assertEquals("Retry", model.interactionState.value.commentText)
        assertEquals(1, service.writes)
    }

    @Test fun firstUseCandidatesLoadWithReadEligibilityWhileWriteIsStillUnverified() = runTest {
        val service = EligibleForumService()
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        model.openCommentMentionPicker()
        advanceUntilIdle()
        model.selectCommentMention(CANDIDATE)
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(listOf(OfficialForumCommentMention(CANDIDATE.uuid, CANDIDATE.name)), service.mentions)
        assertEquals(1, service.candidateReads)
        assertEquals(1, service.writes)
        assertFalse(model.canWrite)
    }

    @Test fun clearingStoreDisablesAttemptsAndRejectsStaleEditorCalls() = runTest {
        val service = EligibleForumService()
        val model = OfficialForumDetailViewModel(service, 42)
        val store = ViewModelStore().apply { put("detail", model) }
        advanceUntilIdle()
        model.openCommentComposer()
        model.updateCommentDraft("Old account")
        val revision = model.commentDraftRevision
        store.clear()
        assertTrue(model.commentDraftRevision > revision)
        assertFalse(model.canInteract)
        assertFalse(model.canAttachCommentImage)
        model.updateCommentDraft("Late input")
        model.submitComment(OfficialForumReplyTarget(0, 0), "Late send")
        advanceUntilIdle()
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertEquals(0, service.writes)
    }
}

private class EligibleForumService : OfficialForumImageUploadService, OfficialForumActionEligibilityService,
    OfficialForumCommentAuthoringService {
    override var canPerformAuthenticatedWrites = false
    override var canUploadCommentImages = false
    override var canAttemptAuthenticatedWrites = true
    override var canAttemptCommentImageUpload = true
    override var canReadMentionCandidates = true
    var grantOnSuccess = false
    var writes = 0
    var uploads = 0
    var candidateReads = 0
    var nextWriteFailure: Exception? = null
    var writeGate: CompletableDeferred<Unit>? = null
    var uploadGate: CompletableDeferred<Unit>? = null
    var ignoreWriteCancellation = false
    var afterUpload: (() -> Unit)? = null
    var mentions = emptyList<OfficialForumCommentMention>()
    val comments = mutableListOf<OfficialForumCommentDraft>()
    private suspend fun write() {
        writes++
        if (ignoreWriteCancellation) withContext(NonCancellable) { writeGate?.await() } else writeGate?.await()
        nextWriteFailure?.let { nextWriteFailure = null; throw it }
        if (grantOnSuccess) canPerformAuthenticatedWrites = true
    }
    override suspend fun fetchParts() = emptyList<OfficialForumPartFilter>()
    override suspend fun fetchPosts(query: OfficialForumListQuery) = OfficialForumPage<OfficialForumPostSummary>(emptyList(), 0, query.page)
    override suspend fun searchPosts(query: OfficialForumSearchQuery) = OfficialForumPage<OfficialForumPostSummary>(emptyList(), 0, query.page)
    override suspend fun fetchPostDetail(id: Int) = DETAIL
    override suspend fun fetchComments(query: OfficialForumCommentQuery) = OfficialForumPage(listOf(ROOT), 1, query.page)
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery) = OfficialForumPage<OfficialForumComment>(emptyList(), 0, query.page)
    override suspend fun likePost(id: Int): Int { write(); return 1 }
    override suspend fun starPost(id: Int): Int { write(); return 1 }
    override suspend fun likeComment(id: Int): Int { write(); return 1 }
    override suspend fun deleteComment(id: Int) { write() }
    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult { write(); return OfficialForumVoteResult(1, mapOf(1 to 1)) }
    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> { comments += draft; write(); return listOf(10) }
    override suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload): String {
        uploads++
        uploadGate?.await()
        afterUpload?.invoke()
        if (grantOnSuccess) canUploadCommentImages = true
        return "fixture-image"
    }
    override suspend fun fetchMentionCandidates(): List<OfficialForumMentionCandidate> { candidateReads++; return listOf(CANDIDATE) }
    override suspend fun submitCommentWithMentions(draft: OfficialForumCommentDraft, mentions: List<OfficialForumCommentMention>): List<Int> {
        this.mentions = mentions
        return submitComment(draft)
    }
}

private val AUTHOR = OfficialForumAuthor("fixture", "Fixture", "World", "Area", null, 0)
private val ROOT = OfficialForumComment(9, AUTHOR, null, "Root", emptyList(), emptyList(), null, null, 0, false, 0, isPostAuthor = false, isMine = true)
private val VOTE = OfficialForumPostVote("vote", "Choice", 1, 1, 1, 0, null, 4,
    listOf(OfficialForumPostVoteOption("one", 1, "One", null, 1, 1, false)))
private val DETAIL = OfficialForumPostDetail(42, "Fixture", "<p>Body</p>", "Body", emptyList(), emptyList(), emptyList(), listOf(VOTE),
    AUTHOR, OfficialForumPart(1, "Part", "Parent"), null, null, null, 1, 2, 3, false, false, 4, null, false, false)
private val IMAGE = OfficialForumCommentImageUpload(byteArrayOf(1, 2, 3), "image/png")
private val CANDIDATE = OfficialForumMentionCandidate("fixture-person", "Fixture person", null)
