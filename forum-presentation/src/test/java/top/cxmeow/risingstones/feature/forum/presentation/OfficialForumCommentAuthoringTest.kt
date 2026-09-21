package top.cxmeow.risingstones.feature.forum.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class OfficialForumCommentAuthoringTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun candidatesLoadOnlyOnExplicitPickerOpenAndQueryNeverRequestsAgain() = runTest {
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        model.refreshCommentMentionCandidates()
        model.updateCommentMentionQuery("Before opening")
        model.openCommentEmojiPicker()
        advanceUntilIdle()
        assertEquals(0, service.candidateReads)
        model.openCommentMentionPicker()
        model.openCommentMentionPicker()
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        assertEquals(1, service.candidateReads)
        assertFalse(model.commentEditorState.value.isEmojiPickerOpen)
        assertEquals(listOf(CANDIDATE), model.commentEditorState.value.candidates)
        model.updateCommentMentionQuery("No match")
        model.updateCommentMentionQuery("Candidate")
        advanceUntilIdle()
        assertEquals(1, service.candidateReads)
        assertEquals("Candidate", model.commentEditorState.value.candidateQuery)
        assertEquals(listOf(CANDIDATE), model.commentEditorState.value.candidates)
        assertTrue(service.richSubmissions.isEmpty())
        assertTrue(service.plainSubmissions.isEmpty())
    }

    @Test fun confirmedEmptyCandidatesAreCachedAcrossClosingAndReopeningComposer() = runTest {
        val service = CommentAuthoringFixture().apply { candidates = { emptyList() } }
        val model = loadedModel(service)
        model.openCommentMentionPicker()
        advanceUntilIdle()
        assertEquals(OfficialForumLoadStatus.Loaded, model.commentEditorState.value.candidateStatus)
        assertTrue(model.commentEditorState.value.candidates.isEmpty())
        model.closeCommentComposer()
        model.resumeCommentComposer()
        model.openCommentMentionPicker()
        advanceUntilIdle()
        assertEquals(1, service.candidateReads)
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        assertEquals(2, service.candidateReads)
    }

    @Test fun forumWriteAndAccountReadAreIndependentGatesForCandidateLookup() = runTest {
        listOf(false to true, true to false, false to false).forEach { (write, account) ->
            val service = CommentAuthoringFixture().apply {
                canPerformAuthenticatedWrites = write
                canReadMentionCandidates = account
            }
            val model = loadedModel(service)
            model.openCommentMentionPicker()
            model.refreshCommentMentionCandidates()
            model.selectCommentMention(CANDIDATE)
            advanceUntilIdle()
            assertEquals(0, service.candidateReads)
            assertTrue(model.commentEditorState.value.candidates.isEmpty())
            assertFalse(model.commentEditorState.value.isMentionPickerOpen)
            assertTrue(service.richSubmissions.isEmpty())
        }
    }

    @Test fun legacyServiceKeepsPlainDraftAndStillSubmitsWithoutAuthoringExtension() = runTest {
        val service = CommentAuthoringFixture()
        val legacy = object : OfficialForumService by service {}
        val model = loadedModel(legacy)
        model.updateCommentEditor("Hello @Candidate", 6, 6)
        model.openCommentMentionPicker()
        assertEquals("Hello @Candidate", model.commentEditorState.value.text)
        assertEquals(6, model.commentEditorState.value.selectionStart)
        assertTrue(model.interactionState.value.isComposerOpen)
        assertEquals(OfficialForumInteractionError.Unavailable, model.interactionState.value.commentError)
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(0, service.candidateReads)
        assertEquals("<p>Hello @Candidate</p>", service.plainSubmissions.single().contentHtml)
        assertTrue(service.richSubmissions.isEmpty())
    }

    @Test fun closingPickerCancelsPendingLookupAndLateResultCannotReplaceReopenedCandidates() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = CommentAuthoringFixture().apply {
            candidates = { withContext(NonCancellable) { gate.await() }; listOf(CANDIDATE.copy(uuid = "fixture-old")) }
        }
        val model = loadedModel(service)
        model.openCommentMentionPicker()
        runCurrent()
        assertEquals(OfficialForumLoadStatus.Loading, model.commentEditorState.value.candidateStatus)
        model.closeCommentMentionPicker()
        assertEquals(OfficialForumLoadStatus.Idle, model.commentEditorState.value.candidateStatus)
        service.candidates = { listOf(CANDIDATE) }
        model.openCommentMentionPicker()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, service.candidateReads)
        assertEquals(listOf(CANDIDATE), model.commentEditorState.value.candidates)
        assertNull(model.commentEditorState.value.candidateError)
    }

    @Test fun closingComposerActuallyCancelsCooperativeLookupWithoutOrdinaryError() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val service = CommentAuthoringFixture().apply {
            candidates = { try { gate.await(); listOf(CANDIDATE) } finally { cancelled = true } }
        }
        val model = loadedModel(service)
        model.updateCommentDraft("Keep me")
        model.openCommentMentionPicker()
        runCurrent()
        model.closeCommentComposer()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("Keep me", model.commentEditorState.value.text)
        assertFalse(model.commentEditorState.value.isMentionPickerOpen)
        assertNull(model.commentEditorState.value.candidateError)
        assertNull(model.interactionState.value.commentError)
    }

    @Test fun refreshFailureRetainsConfirmedCandidatesAndSelectionStillUsesThosePeople() = runTest {
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        model.openCommentMentionPicker()
        advanceUntilIdle()
        service.candidates = { error("synthetic unavailable") }
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        assertEquals(listOf(CANDIDATE), model.commentEditorState.value.candidates)
        assertEquals(OfficialForumLoadStatus.Failed, model.commentEditorState.value.candidateStatus)
        assertEquals(OfficialForumInteractionError.Failed, model.commentEditorState.value.candidateError)
        model.closeCommentMentionPicker()
        model.openCommentMentionPicker()
        advanceUntilIdle()
        assertEquals(2, service.candidateReads)
        model.selectCommentMention(CANDIDATE)
        assertEquals(listOf(MENTION), model.commentEditorState.value.prepareComment().mentions)
        service.candidates = { emptyList() }
        model.openCommentMentionPicker()
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        assertTrue(model.commentEditorState.value.candidates.isEmpty())
        assertNull(model.commentEditorState.value.candidateError)
    }

    @Test fun onlyCurrentConfirmedCandidatePairsCanBeSelectedAndAliasesAreNotCollapsed() = runTest {
        val alias = CANDIDATE.copy(name = "Alias")
        val service = CommentAuthoringFixture().apply {
            candidates = { listOf(CANDIDATE, CANDIDATE.copy(avatarUrl = "ignored"), alias,
                CANDIDATE.copy(uuid = "bad#uuid"), CANDIDATE.copy(name = "bad\nname")) }
        }
        val model = loadedModel(service)
        model.openCommentMentionPicker()
        advanceUntilIdle()
        assertEquals(listOf(CANDIDATE, alias), model.commentEditorState.value.candidates)
        model.selectCommentMention(CANDIDATE.copy(uuid = "fixture-unknown"))
        model.selectCommentMention(CANDIDATE.copy(name = "Not a candidate"))
        assertEquals("", model.commentEditorState.value.text)
        model.selectCommentMention(alias.copy(avatarUrl = "untrusted extra metadata"))
        assertEquals("@Alias ", model.commentEditorState.value.text)
        assertEquals(listOf(MENTION.copy(name = "Alias")), model.commentEditorState.value.prepareComment().mentions)
        model.selectCommentMention(CANDIDATE)
        assertEquals("@Alias ", model.commentEditorState.value.text)
        model.openCommentMentionPicker()
        service.candidates = { emptyList() }
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        model.selectCommentMention(CANDIDATE)
        assertEquals("@Alias ", model.commentEditorState.value.text)
    }

    @Test fun closingAndResumingDraftKeepsReplyTargetSelectionAndMentionObjects() = runTest {
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        model.openCommentComposer(OfficialForumReplyTarget(9, 9, "Root"))
        selectCandidate(model)
        val text = model.commentEditorState.value.text
        model.updateCommentEditor(text, 0, text.length - 1)
        val range = model.commentEditorState.value.mentions.single()
        model.openCommentEmojiPicker()
        model.closeCommentComposer()
        model.resumeCommentComposer()
        assertEquals(OfficialForumReplyTarget(9, 9, "Root"), model.interactionState.value.replyTarget)
        assertEquals(0, model.commentEditorState.value.selectionStart)
        assertEquals(text.length - 1, model.commentEditorState.value.selectionEnd)
        assertSame(range, model.commentEditorState.value.mentions.single())
        assertFalse(model.commentEditorState.value.isEmojiPickerOpen)
        assertFalse(model.commentEditorState.value.isMentionPickerOpen)
        assertEquals(1, service.candidateReads)
        assertTrue(service.richSubmissions.isEmpty())
    }

    @Test fun emojiInsertionUsesSelectionAndOnlyOfficialNumbersAreAccepted() = runTest {
        val model = loadedModel(CommentAuthoringFixture())
        model.updateCommentEditor("A😀B", 1, 3)
        model.openCommentEmojiPicker()
        model.insertCommentEmoji(0)
        model.insertCommentEmoji(47)
        assertEquals("A😀B", model.commentEditorState.value.text)
        assertTrue(model.commentEditorState.value.isEmojiPickerOpen)
        model.insertCommentEmoji(46)
        assertEquals("A[emo46]B", model.interactionState.value.commentText)
        assertEquals(8, model.commentEditorState.value.selectionStart)
        assertFalse(model.commentEditorState.value.isEmojiPickerOpen)
        model.closeCommentComposer()
        model.insertCommentEmoji(1)
        assertEquals("A[emo46]B", model.commentEditorState.value.text)
    }

    @Test fun cancellationOfInitialOrCachedRefreshResetsPendingWithoutFailedState() = runTest {
        val service = CommentAuthoringFixture().apply { candidates = { throw CancellationException() } }
        val model = loadedModel(service)
        model.openCommentMentionPicker()
        advanceUntilIdle()
        assertEquals(OfficialForumLoadStatus.Idle, model.commentEditorState.value.candidateStatus)
        assertNull(model.commentEditorState.value.candidateError)
        service.candidates = { listOf(CANDIDATE) }
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        service.candidates = { throw CancellationException() }
        model.refreshCommentMentionCandidates()
        advanceUntilIdle()
        assertEquals(OfficialForumLoadStatus.Loaded, model.commentEditorState.value.candidateStatus)
        assertEquals(listOf(CANDIDATE), model.commentEditorState.value.candidates)
        assertNull(model.commentEditorState.value.candidateError)
        assertFalse(model.state.value.actionFailed)
    }

    @Test fun revokedAccountReadDuringPendingLookupCannotPublishCandidatesOrSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = CommentAuthoringFixture().apply { candidates = { gate.await(); listOf(CANDIDATE) } }
        val model = loadedModel(service)
        model.updateCommentDraft("Draft")
        model.openCommentMentionPicker()
        runCurrent()
        service.canReadMentionCandidates = false
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertEquals(OfficialForumInteractionError.AuthenticationRequired, model.interactionState.value.commentError)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
        assertTrue(service.richSubmissions.isEmpty())
    }

    @Test fun cachedIdentityIsClearedWhenOnlyAccountReadIsRevokedBeforeSubmitting() = runTest {
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        selectCandidate(model)
        model.setCommentImage(IMAGE)
        service.canReadMentionCandidates = false
        assertTrue(model.canWrite)
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertNull(model.interactionState.value.commentImage)
        assertEquals(OfficialForumInteractionError.Unavailable, model.interactionState.value.commentError)
        assertEquals(0, service.uploads)
        assertTrue(service.richSubmissions.isEmpty())
        assertTrue(service.plainSubmissions.isEmpty())
    }

    @Test fun revokedWriteBeforeSelectionClearsCachedAuthoringContentAndDoesNotSelect() = runTest {
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        model.updateCommentDraft("Draft")
        model.openCommentMentionPicker()
        advanceUntilIdle()
        service.canPerformAuthenticatedWrites = false
        model.selectCommentMention(CANDIDATE)
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertFalse(model.interactionState.value.isComposerOpen)
        assertEquals(OfficialForumInteractionError.Unavailable, model.interactionState.value.actionError)
    }

    @Test fun clearProtectedContentReleasesCandidatesDraftAndLateRefreshCannotRestoreThem() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        selectCandidate(model)
        model.setCommentImage(IMAGE)
        service.candidates = { withContext(NonCancellable) { gate.await() }; listOf(CANDIDATE) }
        model.openCommentMentionPicker()
        model.refreshCommentMentionCandidates()
        runCurrent()
        model.clearProtectedContent()
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        service.candidates = { emptyList() }
        model.openCommentComposer()
        model.openCommentMentionPicker()
        advanceUntilIdle()
        assertEquals(3, service.candidateReads)
        assertEquals(OfficialForumLoadStatus.Loaded, model.commentEditorState.value.candidateStatus)
    }

    @Test fun clearingViewModelStoreReleasesDraftAndPreventsLateReadOrFutureEditorActions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = CommentAuthoringFixture().apply {
            candidates = { withContext(NonCancellable) { gate.await() }; listOf(CANDIDATE) }
        }
        val model = loadedModel(service)
        val store = ViewModelStore().apply { put("detail", model) }
        model.updateCommentDraft("Draft")
        model.setCommentImage(IMAGE)
        model.openCommentMentionPicker()
        runCurrent()
        store.clear()
        model.updateCommentEditor("Stale UI", 0, 0)
        model.updateCommentMentionQuery("Stale query")
        model.openCommentComposer()
        model.openCommentMentionPicker()
        model.selectCommentMention(CANDIDATE)
        model.submitDraftComment()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertEquals(OfficialForumDetailInteractionState(), model.interactionState.value)
        assertEquals(1, service.candidateReads)
        assertTrue(service.richSubmissions.isEmpty())
    }

    @Test fun successfulRichSubmitRunsOnceAndClearsDraftSelectionCandidatesAndImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = CommentAuthoringFixture().apply { richSubmit = { _, _ -> gate.await(); listOf(10) } }
        val model = loadedModel(service)
        selectCandidate(model)
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        model.submitDraftComment()
        runCurrent()
        assertTrue(model.state.value.isSubmittingComment)
        assertEquals(1, service.richSubmissions.size)
        assertEquals(listOf(MENTION), service.richSubmissions.single().second)
        model.updateCommentDraft("Pending edit")
        model.discardCommentDraft()
        model.closeCommentComposer()
        model.openCommentMentionPicker()
        assertEquals("@Candidate ", model.commentEditorState.value.text)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, service.richSubmissions.size)
        assertEquals(1, service.uploads)
        assertTrue(service.plainSubmissions.isEmpty())
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
        assertNull(model.interactionState.value.commentImage)
        assertFalse(model.interactionState.value.isComposerOpen)
        assertFalse(model.state.value.isSubmittingComment)
    }

    @Test fun failedRichSubmitKeepsIdentityAndSuccessfulUploadIsReusedOnSingleRetry() = runTest {
        val service = CommentAuthoringFixture().apply { richSubmit = { _, _ -> error("synthetic failure") } }
        val model = loadedModel(service)
        model.openCommentComposer(OfficialForumReplyTarget(9, 9))
        selectCandidate(model)
        model.setCommentImage(IMAGE)
        val range = model.commentEditorState.value.mentions.single()
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(OfficialForumInteractionError.Failed, model.interactionState.value.commentError)
        assertSame(range, model.commentEditorState.value.mentions.single())
        assertEquals("@Candidate ", model.interactionState.value.commentText)
        assertNotNull(model.interactionState.value.commentImage)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
        service.richSubmit = { _, _ -> listOf(10) }
        model.submitDraftComment()
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(1, service.uploads)
        assertEquals(2, service.richSubmissions.size)
        assertEquals(service.richSubmissions[0], service.richSubmissions[1])
        assertEquals(9, service.richSubmissions.last().first.parentId)
        assertEquals(9, service.richSubmissions.last().first.rootParentId)
        assertEquals("https://fixture.test/comment-image", service.richSubmissions.last().first.commentPictureText)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
    }

    @Test fun legacySubmitWithMatchingTextNeverBorrowsEditorMetadataEvenOnDraftRetry() = runTest {
        val service = CommentAuthoringFixture().apply { plainSubmit = { error("synthetic failure") } }
        val model = loadedModel(service)
        selectCandidate(model)
        model.submitComment(OfficialForumReplyTarget(0, 0), model.commentEditorState.value.text)
        advanceUntilIdle()
        assertTrue(model.commentEditorState.value.mentions.isEmpty())
        assertEquals("<p>@Candidate</p>", service.plainSubmissions.single().contentHtml)
        service.plainSubmit = { listOf(10) }
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(2, service.plainSubmissions.size)
        assertTrue(service.richSubmissions.isEmpty())
        assertEquals(service.plainSubmissions[0], service.plainSubmissions[1])
    }

    @Test fun legacyFullTextUpdateCannotRetainNotificationIdentityWhenTextChanges() = runTest {
        val service = CommentAuthoringFixture()
        val model = loadedModel(service)
        selectCandidate(model)
        model.updateCommentDraft("@Candidate edited")
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals("<p>@Candidate edited</p>", service.plainSubmissions.single().contentHtml)
        assertTrue(service.richSubmissions.isEmpty())
    }

    @Test fun cancelledRichWriteKeepsRetryableIdentityAndDoesNotReportBusinessFailure() = runTest {
        val service = CommentAuthoringFixture().apply { richSubmit = { _, _ -> throw CancellationException() } }
        val model = loadedModel(service)
        selectCandidate(model)
        model.submitDraftComment()
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmittingComment)
        assertFalse(model.state.value.actionFailed)
        assertNull(model.interactionState.value.commentError)
        assertEquals(listOf(MENTION), model.commentEditorState.value.prepareComment().mentions)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
        service.richSubmit = { _, _ -> listOf(10) }
        model.submitDraftComment()
        advanceUntilIdle()
        assertEquals(2, service.richSubmissions.size)
        assertEquals(1L, model.interactionState.value.commentSuccessRevision)
    }

    @Test fun accountReadRevokedDuringImageUploadPreventsMentionSubmissionAndClearsDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = CommentAuthoringFixture().apply { upload = { gate.await(); "https://fixture.test/comment-image" } }
        val model = loadedModel(service)
        selectCandidate(model)
        model.setCommentImage(IMAGE)
        model.submitDraftComment()
        runCurrent()
        service.canReadMentionCandidates = false
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, service.uploads)
        assertTrue(service.richSubmissions.isEmpty())
        assertTrue(service.plainSubmissions.isEmpty())
        assertEquals(OfficialForumCommentEditorState(), model.commentEditorState.value)
        assertEquals(OfficialForumInteractionError.AuthenticationRequired, model.interactionState.value.commentError)
        assertEquals(0L, model.interactionState.value.commentSuccessRevision)
    }

    private fun TestScope.loadedModel(service: OfficialForumService): OfficialForumDetailViewModel {
        val model = OfficialForumDetailViewModel(service, 42)
        advanceUntilIdle()
        model.openCommentComposer()
        return model
    }

    private fun TestScope.selectCandidate(model: OfficialForumDetailViewModel) {
        model.openCommentMentionPicker()
        advanceUntilIdle()
        model.selectCommentMention(CANDIDATE)
        assertEquals(listOf(MENTION), model.commentEditorState.value.prepareComment().mentions)
    }
}

private class CommentAuthoringFixture : OfficialForumCommentAuthoringService, OfficialForumImageUploadService {
    override var canPerformAuthenticatedWrites = true
    override var canReadMentionCandidates = true
    override var canUploadCommentImages = true
    var candidateReads = 0
    var uploads = 0
    var candidates: suspend () -> List<OfficialForumMentionCandidate> = { listOf(CANDIDATE) }
    var upload: suspend () -> String = { "https://fixture.test/comment-image" }
    var plainSubmit: suspend (OfficialForumCommentDraft) -> List<Int> = { listOf(10) }
    var richSubmit: suspend (OfficialForumCommentDraft, List<OfficialForumCommentMention>) -> List<Int> = { _, _ -> listOf(10) }
    val plainSubmissions = mutableListOf<OfficialForumCommentDraft>()
    val richSubmissions = mutableListOf<Pair<OfficialForumCommentDraft, List<OfficialForumCommentMention>>>()
    override suspend fun fetchMentionCandidates(): List<OfficialForumMentionCandidate> { candidateReads++; return candidates() }
    override suspend fun submitCommentWithMentions(draft: OfficialForumCommentDraft, mentions: List<OfficialForumCommentMention>): List<Int> {
        richSubmissions += draft to mentions.toList()
        return richSubmit(draft, mentions)
    }
    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> { plainSubmissions += draft; return plainSubmit(draft) }
    override suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload): String { uploads++; return upload() }
    override suspend fun fetchParts() = emptyList<OfficialForumPartFilter>()
    override suspend fun fetchPosts(query: OfficialForumListQuery) = OfficialForumPage<OfficialForumPostSummary>(emptyList(), 0, query.page)
    override suspend fun searchPosts(query: OfficialForumSearchQuery) = OfficialForumPage<OfficialForumPostSummary>(emptyList(), 0, query.page)
    override suspend fun fetchPostDetail(id: Int) = DETAIL
    override suspend fun fetchComments(query: OfficialForumCommentQuery) = OfficialForumPage(listOf(ROOT), 1, query.page)
    override suspend fun fetchSubComments(query: OfficialForumSubCommentQuery) = OfficialForumPage<OfficialForumComment>(emptyList(), 0, query.page)
    override suspend fun likePost(id: Int): Int = error("Unexpected fixture call")
    override suspend fun likeComment(id: Int): Int = error("Unexpected fixture call")
    override suspend fun starPost(id: Int): Int = error("Unexpected fixture call")
    override suspend fun deleteComment(id: Int): Unit = error("Unexpected fixture call")
    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult = error("Unexpected fixture call")
}

private val CANDIDATE = OfficialForumMentionCandidate("fixture-one", "Candidate", areaName = "Area", groupName = "World")
private val MENTION = OfficialForumCommentMention(CANDIDATE.uuid, CANDIDATE.name)
private val AUTHOR = OfficialForumAuthor("fixture-author", "Author", "World", "Area", null, 0)
private val ROOT = OfficialForumComment(9, AUTHOR, null, "Root", emptyList(), emptyList(), null, null, 0, false, 0,
    isPostAuthor = false, isMine = true)
private val DETAIL = OfficialForumPostDetail(42, "Fixture", "<p>Body</p>", "Body", emptyList(), emptyList(), emptyList(), emptyList(),
    AUTHOR, OfficialForumPart(1, "Part", "Parent"), null, null, null, 1, 2, 3, false, false, 4, null, false, false)
private val IMAGE = OfficialForumCommentImageUpload(byteArrayOf(1, 2, 3), "image/png")
