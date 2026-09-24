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
class DynamicPublishingViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun imagesAreReadOnlyForExplicitSubmissionAndTheirFinalOrderIsPreserved() = runTest {
        val service = PublishingFixture()
        val model = DynamicPublishingViewModel(service, service, service)
        val sources = (1..3).map(::ImageSource)
        model.initialize()
        model.addImages(sources)
        model.moveImage(model.state.value.images.last().id, -1)
        model.removeImage(model.state.value.images.first().id)
        model.setVisibility(DynamicVisibility.MutualFollowers)
        model.updateEditor("body [emo46]", 12, 12)
        advanceUntilIdle()
        assertTrue(sources.all { it.reads == 0 })
        model.submit()
        advanceUntilIdle()
        val draft = service.published.single()
        assertEquals(DynamicVisibility.MutualFollowers, draft.visibility)
        assertEquals(listOf(3, 2), draft.images.map { (it as PublishingImage).number })
        assertTrue(draft.contentHtml.contains("<span class=\"at-emo\" contenteditable=\"false\">[emo46]</span>"))
        assertFalse(draft.contentHtml.contains("<img"))
        assertEquals(0, sources.first().reads)
    }

    @Test fun imageOnlyOriginalCannotUploadOrPublish() = runTest {
        val service = PublishingFixture()
        val model = DynamicPublishingViewModel(service, service, service)
        val source = ImageSource(1)
        model.addImages(listOf(source))
        model.submit()
        advanceUntilIdle()
        assertEquals(DynamicPublishingError.ContentRequired, model.state.value.error)
        assertEquals(0, source.reads)
        assertTrue(service.published.isEmpty())
    }

    @Test fun explicitRetryReusesSuccessfulUploadsAfterReordering() = runTest {
        val service = PublishingFixture().apply { failImageOnce = 2 }
        val model = DynamicPublishingViewModel(service, service, service)
        val sources = (1..3).map(::ImageSource)
        model.addImages(sources)
        model.updateEditor("draft", 5, 5)
        model.submit()
        advanceUntilIdle()
        assertEquals(DynamicPublishingError.UploadFailed, model.state.value.error)
        assertFalse(model.state.value.isSubmitting)
        assertEquals(3, model.state.value.images.size)
        model.moveImage(model.state.value.images.first().id, 1)
        model.submit()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1), sources.map { it.reads })
        assertEquals(listOf(2, 1, 3), service.published.single().images.map { (it as PublishingImage).number })
        assertEquals(listOf(1, 2, 2, 3), service.uploadedNumbers)
    }

    @Test fun relayHasNoImageAuthoringAndKeepsPostNamespaceAndVisibility() = runTest {
        val service = PublishingFixture()
        val model = DynamicPublishingViewModel(service, service, service, 17, "Guide title")
        assertFalse(model.canUpload)
        model.addImages(listOf(ImageSource(1)))
        model.setVisibility(DynamicVisibility.OnlyMe)
        model.submit()
        advanceUntilIdle()
        assertTrue(service.published.isEmpty())
        val draft = service.relayed.single()
        assertEquals(17, draft.postId)
        assertEquals(DynamicVisibility.OnlyMe, draft.visibility)
        assertTrue(draft.contentHtml.isEmpty())
        assertTrue(service.uploadedNumbers.isEmpty())
    }

    @Test fun doubleSubmissionUsesOneScopeAndCompletionCanOnlyBeConsumedOnce() = runTest {
        val service = PublishingFixture().apply { publishingGate = CompletableDeferred() }
        val model = DynamicPublishingViewModel(service, service)
        model.initialize()
        model.updateEditor("once", 4, 4)
        model.submit()
        model.submit()
        runCurrent()
        assertEquals(1, service.published.size)
        assertEquals(1, service.scopes.size)
        assertFalse(model.requestClose())
        service.publishingGate?.complete(Unit)
        advanceUntilIdle()
        val result = requireNotNull(model.state.value.completedId)
        assertTrue(model.takePublishedResult(result))
        assertFalse(model.takePublishedResult(result))
        assertFalse(service.scopes.single().current)
    }

    @Test fun scopeCreationFailurePreservesDraftAndReleasesBusyForRetry() = runTest {
        val service = PublishingFixture().apply { failScope = true }
        val model = DynamicPublishingViewModel(service, service)
        model.updateEditor("retry", 5, 5)
        model.submit()
        advanceUntilIdle()
        assertFalse(model.state.value.isSubmitting)
        assertEquals("retry", model.state.value.editor.text)
        service.failScope = false
        model.submit()
        advanceUntilIdle()
        assertEquals(1, service.published.size)
    }

    @Test fun clearingCredentialStoreRejectsLateLocalImageWithoutUploading() = runTest {
        val service = PublishingFixture()
        val owner = ViewModelStore()
        val model = DynamicPublishingViewModel(service, service, service)
        owner.put("publishing", model)
        val gate = CompletableDeferred<Unit>()
        model.addImages(listOf(DynamicPublishingImageSource {
            withContext(NonCancellable) { gate.await() }
            DynamicImageUploadInput(byteArrayOf(1), "image/png")
        }))
        model.updateEditor("private", 7, 7)
        model.submit()
        runCurrent()
        owner.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(service.canAttemptAuthenticatedWrites)
        assertEquals("", model.state.value.editor.text)
        assertTrue(model.state.value.images.isEmpty())
        assertTrue(service.uploadedNumbers.isEmpty())
        assertTrue(service.published.isEmpty())
        assertFalse(service.scopes.single().current)
    }

    @Test fun discardInvalidatesPickerAndOversizedSelectionIsNotPartiallyAdded() = runTest {
        val service = PublishingFixture()
        val model = DynamicPublishingViewModel(service, service, service)
        model.addImages((1..10).map(::ImageSource))
        assertEquals(DynamicPublishingError.TooManyImages, model.state.value.error)
        assertTrue(model.state.value.images.isEmpty())
        model.addImages(listOf(ImageSource(1)))
        val ticket = requireNotNull(model.beginImagePicker())
        assertFalse(model.requestClose())
        assertTrue(model.state.value.confirmDiscard)
        model.cancelDiscard()
        assertEquals(1, model.state.value.images.size)
        model.discard()
        model.acceptImagePicker(ticket, listOf(ImageSource(2)))
        assertTrue(model.state.value.images.isEmpty())
        advanceUntilIdle()
        assertTrue(service.published.isEmpty())
    }

    @Test fun publishingKeepsMentionIdentityAndDoesNotConvertEmojiInsideItsName() = runTest {
        val service = PublishingFixture()
        val model = DynamicPublishingViewModel(service, service)
        val mention = DynamicCommentMention("fixture", "Name[emo1]")
        model.selectMention(mention)
        model.insertEmoji(2)
        model.submit()
        advanceUntilIdle()
        val draft = service.published.single()
        assertEquals(listOf(mention), draft.mentions)
        assertTrue(draft.contentHtml.contains(">@Name[emo1]</span>"))
        assertTrue(draft.contentHtml.contains(">[emo2]</span>"))
    }

    @Test fun losingWriteEligibilityClearsRelaySourceAndCannotRestoreOldDraft() = runTest {
        val service = PublishingFixture()
        val model = DynamicPublishingViewModel(service, service, relayPostId = 17, relayPostTitle = "Private source")
        model.initialize()
        model.updateEditor("Private draft", 13, 13)
        advanceUntilIdle()
        service.canAttemptAuthenticatedWrites = false
        model.synchronizeAccess()
        service.canAttemptAuthenticatedWrites = true
        model.submit()
        advanceUntilIdle()
        assertNull(model.state.value.relayPostTitle)
        assertEquals("", model.state.value.editor.text)
        assertFalse(model.state.value.usable)
        assertTrue(service.relayed.isEmpty())
        assertFalse(service.scopes.single().current)
    }
}

private class ImageSource(private val number: Int) : DynamicPublishingImageSource {
    var reads = 0
    override suspend fun read(): DynamicImageUploadInput { reads++; return DynamicImageUploadInput(byteArrayOf(number.toByte()), "image/png") }
}
private data class PublishingImage(val number: Int) : DynamicUploadedImage
private class PublishingScope(var current: Boolean = true) : DynamicActionScope {
    override suspend fun isCurrent() = current
    override fun close() { current = false }
}
private class PublishingFixture : DynamicActionService, DynamicPublishingService,
    DynamicImageUploadService, DynamicPublishingImageUploadService {
    override val canPerformAuthenticatedWrites = false
    override var canAttemptAuthenticatedWrites = true
    override val canUploadImages = false
    override val canAttemptImageUpload = true
    var failScope = false
    var failImageOnce: Int? = null
    var publishingGate: CompletableDeferred<Unit>? = null
    val scopes = mutableListOf<PublishingScope>()
    val published = mutableListOf<DynamicPublishDraft>()
    val relayed = mutableListOf<DynamicPostRelayDraft>()
    val uploadedNumbers = mutableListOf<Int>()
    override suspend fun beginActionScope(): DynamicActionScope {
        if (failScope) throw DynamicException.Network
        return PublishingScope().also(scopes::add)
    }
    override suspend fun publish(scope: DynamicActionScope, draft: DynamicPublishDraft) { published += draft; publishingGate?.await() }
    override suspend fun relayPost(scope: DynamicActionScope, draft: DynamicPostRelayDraft) { relayed += draft }
    override suspend fun uploadPublishingImage(scope: DynamicActionScope, input: DynamicImageUploadInput): DynamicUploadedImage {
        val number = input.copyBytes().single().toInt()
        uploadedNumbers += number
        if (number == failImageOnce) { failImageOnce = null; throw DynamicException.ImageUploadFailed }
        return PublishingImage(number)
    }
    override suspend fun uploadCommentImage(scope: DynamicActionScope, input: DynamicImageUploadInput): DynamicUploadedImage = error("comment upload must not be used")
    override suspend fun entryEligibility(scope: DynamicActionScope, dynamicId: Int) = DynamicEntryActionEligibility(dynamicId, DynamicActionEligibility.Unknown)
    override suspend fun commentEligibility(scope: DynamicActionScope, commentId: Int) = DynamicCommentActionEligibility(commentId, DynamicActionEligibility.Unknown)
    override suspend fun fetchMentionCandidates(scope: DynamicActionScope, query: DynamicListQuery) = DynamicPage<DynamicCommentMention>(emptyList(), query.page, false)
    override suspend fun fetchComments(scope: DynamicActionScope, dynamicId: Int, query: DynamicListQuery) = DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun fetchReplies(scope: DynamicActionScope, rootParentId: Int, query: DynamicListQuery) = DynamicPage<DynamicComment>(emptyList(), query.page, false)
    override suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int) = error("not used")
    override suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft) = error("not used")
    override suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int) = error("not used")
    override suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int) = error("not used")
}
