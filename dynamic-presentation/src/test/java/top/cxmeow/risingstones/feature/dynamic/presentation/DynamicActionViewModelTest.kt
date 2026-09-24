package top.cxmeow.risingstones.feature.dynamic.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.ViewModelStore
import top.cxmeow.risingstones.feature.dynamic.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicActionViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun concurrentActionsShareOneBoundScopeAndDuplicateSubmitIsIgnored() = runTest {
        val service = FakeActions()
        val model = DynamicActionViewModel(service)
        model.bind(7)
        model.openComment()
        model.updateEditor("hello", 5, 5)
        model.toggleLike()
        model.submit()
        model.submit()
        advanceUntilIdle()
        assertEquals(1, service.beginCount)
        assertEquals(1, service.comments.size)
        assertEquals(7, service.comments.single().dynamicId)
    }

    @Test fun failedSubmissionKeepsDraftAndExplicitRetryReusesUploadedImage() = runTest {
        val service = FakeActions().apply { failComment = true }
        val model = DynamicActionViewModel(service, service)
        model.bind(1)
        model.openComment()
        model.updateEditor("draft", 5, 5)
        model.selectImage { DynamicImageUploadInput(byteArrayOf(1), "image/png") }
        advanceUntilIdle()
        model.submit()
        advanceUntilIdle()
        assertEquals("draft", model.state.value.editor.text)
        assertNotNull(model.state.value.image)
        service.failComment = false
        model.submit()
        advanceUntilIdle()
        assertEquals(1, service.uploadCount)
        assertFalse(model.state.value.editorOpen)
        assertEquals(1, model.state.value.pendingEvents.size)
    }

    @Test fun resourceChangeRejectsNonCooperativeLateImageAndClearsDraft() = runTest {
        val service = FakeActions()
        val late = CompletableDeferred<Unit>()
        val model = DynamicActionViewModel(service, service)
        model.bind(1)
        model.openComment()
        model.updateEditor("private", 7, 7)
        model.selectImage {
            withContext(NonCancellable) { late.await() }
            DynamicImageUploadInput(byteArrayOf(1), "image/png")
        }
        runCurrent()
        model.bind(2)
        late.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.dynamicId)
        assertEquals("", model.state.value.editor.text)
        assertNull(model.state.value.image)
    }

    @Test fun invalidatedScopeClearsBusyDraftAndDoesNotCaptureReplacementCredential() = runTest {
        val service = FakeActions()
        val model = DynamicActionViewModel(service)
        model.bind(1)
        advanceUntilIdle()
        model.openComment()
        model.updateEditor("secret", 6, 6)
        service.scope.current = false
        model.toggleLike()
        advanceUntilIdle()
        assertNull(model.state.value.dynamicId)
        assertEquals("", model.state.value.editor.text)
        assertFalse(model.state.value.isLiking)
        assertEquals(1, service.beginCount)
    }

    @Test fun visibleReplyOwnershipIsObservedThroughItsRootBeforeEligibility() = runTest {
        val service = FakeActions()
        val model = DynamicActionViewModel(service)
        model.bind(1)
        advanceUntilIdle()
        model.bindCommentEligibility(listOf(10, 21), mapOf(21 to 10))
        advanceUntilIdle()
        assertEquals(listOf(10), service.replyRoots)
        assertEquals(setOf(10, 21), model.state.value.deleteComments)
    }

    @Test fun unavailableAccessSynchronizationIsIdempotent() = runTest {
        val service = FakeActions().apply { attemptWrites = false }
        val model = DynamicActionViewModel(service)
        model.synchronizeAccess()
        val revision = model.state.value.draftRevision
        repeat(4) { model.synchronizeAccess() }
        assertEquals(revision, model.state.value.draftRevision)
    }

    @Test fun pickerResultCannotCrossAResourceChange() = runTest {
        val service = FakeActions()
        val model = DynamicActionViewModel(service, service)
        model.bind(1)
        advanceUntilIdle()
        val ticket = requireNotNull(model.beginImagePicker())
        model.bind(2)
        model.acceptImagePicker(ticket) { DynamicImageUploadInput(byteArrayOf(1), "image/png") }
        advanceUntilIdle()
        assertNull(model.state.value.image)
        assertEquals(2, model.state.value.dynamicId)
    }

    @Test fun pendingEventCannotBeAppliedToAnotherResource() = runTest {
        val service = FakeActions()
        val model = DynamicActionViewModel(service)
        model.bind(1)
        advanceUntilIdle()
        model.toggleLike()
        advanceUntilIdle()
        val event = model.state.value.pendingEvents.single()
        model.bind(2)
        advanceUntilIdle()
        assertNull(model.takeEvent(event.id, 2))
        assertTrue(model.state.value.pendingEvents.isEmpty())
    }

    @Test fun scopeCreationFailureReleasesSubmitAndLikeForExplicitRetry() = runTest {
        val service = FakeActions().apply { failBegin = true }
        val model = DynamicActionViewModel(service)
        model.bind(1)
        advanceUntilIdle()
        model.openComment()
        model.updateEditor("draft", 5, 5)
        model.submit()
        model.toggleLike()
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmitting)
        assertFalse(model.state.value.isLiking)
        assertEquals("draft", model.state.value.editor.text)
        assertTrue(service.comments.isEmpty())

        service.failBegin = false
        model.submit()
        advanceUntilIdle()
        assertEquals(1, service.comments.size)
        assertFalse(model.state.value.editorOpen)
    }

    @Test fun clearingCredentialOwnedModelsDiscardsDraftAndNonCooperativeLateImage() = runTest {
        val service = FakeActions()
        val owner = ViewModelStore()
        val oldModel = DynamicActionViewModel(service, service)
        owner.put("dynamic", oldModel)
        oldModel.bind(1)
        advanceUntilIdle()
        val oldScope = service.scope
        oldModel.openComment()
        oldModel.updateEditor("private", 7, 7)
        val late = CompletableDeferred<Unit>()
        oldModel.selectImage {
            withContext(NonCancellable) { late.await() }
            DynamicImageUploadInput(byteArrayOf(1), "image/png")
        }
        runCurrent()
        owner.clear()
        val newModel = DynamicActionViewModel(service, service)
        owner.put("dynamic", newModel)
        newModel.bind(1)
        late.complete(Unit)
        advanceUntilIdle()
        assertTrue(service.canAttemptAuthenticatedWrites)
        assertFalse(oldScope.current)
        assertEquals("", oldModel.state.value.editor.text)
        assertNull(oldModel.state.value.image)
        assertEquals("", newModel.state.value.editor.text)
        assertNull(newModel.state.value.image)
        assertTrue(service.comments.isEmpty())
        owner.clear()
    }
}

private class FakeScope(var current: Boolean = true) : DynamicActionScope {
    override suspend fun isCurrent() = current
    override fun close() { current = false }
}
private object Uploaded : DynamicUploadedImage
private class FakeActions : DynamicActionService, DynamicImageUploadService {
    override val canPerformAuthenticatedWrites = false
    var attemptWrites = true
    override val canAttemptAuthenticatedWrites get() = attemptWrites
    override val canUploadImages = false
    override val canAttemptImageUpload = true
    var beginCount = 0
    var uploadCount = 0
    var failComment = false
    var failBegin = false
    var scope = FakeScope()
    val comments = mutableListOf<DynamicCommentDraft>()
    val replyRoots = mutableListOf<Int>()
    override suspend fun beginActionScope(): DynamicActionScope {
        beginCount++
        if (failBegin) throw DynamicException.Network
        scope = FakeScope()
        return scope
    }
    override suspend fun entryEligibility(scope: DynamicActionScope, dynamicId: Int) =
        DynamicEntryActionEligibility(dynamicId, DynamicActionEligibility.Eligible)
    override suspend fun commentEligibility(scope: DynamicActionScope, commentId: Int) =
        DynamicCommentActionEligibility(commentId, DynamicActionEligibility.Eligible)
    override suspend fun fetchMentionCandidates(scope: DynamicActionScope, query: DynamicListQuery) =
        DynamicPage(listOf(DynamicCommentMention("one", "One")), query.page, false)
    override suspend fun fetchComments(scope: DynamicActionScope, dynamicId: Int, query: DynamicListQuery) =
        DynamicPage(listOf(comment(10)), query.page, false)
    override suspend fun fetchReplies(scope: DynamicActionScope, rootParentId: Int, query: DynamicListQuery): DynamicPage<DynamicComment> {
        replyRoots += rootParentId
        return DynamicPage(listOf(comment(21)), query.page, false)
    }
    override suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int) = DynamicLikeResult.Liked
    override suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft) { if (failComment) throw DynamicException.Network; comments += draft }
    override suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int) = Unit
    override suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int) = Unit
    override suspend fun uploadCommentImage(scope: DynamicActionScope, input: DynamicImageUploadInput): DynamicUploadedImage { uploadCount++; return Uploaded }
}
private val TestAuthor = DynamicAuthor("id", "Member", "Area", "World", null)
private fun comment(id: Int) = DynamicComment(id, TestAuthor, "body", emptyList(), null, null, 0, 0)
