package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.*

@OptIn(DelicateCoilApi::class)
class DynamicPublishingScreenTest {
    @get:Rule val rule = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val service = PublishingUiService()
    private val width = mutableStateOf(599)
    private var backs = 0
    private var completions = 0
    private lateinit var imageLoader: ImageLoader

    @Before fun syntheticImages() {
        imageLoader = ImageLoader.Builder(InstrumentationRegistry.getInstrumentation().targetContext).components {
            add(Interceptor { ErrorResult(null, it.request, IllegalStateException("Synthetic image unavailable")) })
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
    }

    @After fun clear() {
        rule.runOnIdle { owner.viewModelStore.clear() }
        imageLoader.shutdown()
        SingletonImageLoader.reset()
    }

    @Test fun dirtyBackRequiresExplicitDiscardAndNeverPublishes() {
        show()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("private draft")
        rule.onNodeWithTag("dynamic-publish-back").performClick()
        rule.onNodeWithTag("dynamic-publish-confirm-discard").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithTag("dynamic-comment-input").assertTextContains("private draft")
        assertEquals(0, backs)
        rule.onNodeWithTag("dynamic-publish-back").performClick()
        rule.onNodeWithTag("dynamic-publish-confirm-discard").performClick()
        assertEquals(1, backs)
        assertTrue(service.published.isEmpty())
    }

    @Test fun failedPublishingKeepsDraftAndExplicitRetryCompletesOnce() {
        service.failPublish = true
        show()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("retry draft")
        rule.onNodeWithTag("dynamic-publish-submit").performClick()
        rule.onNodeWithTag("dynamic-publish-error").assertExists()
        rule.onNodeWithTag("dynamic-comment-input").assertTextContains("retry draft")
        assertEquals(0, completions)
        rule.runOnIdle { service.failPublish = false }
        rule.onNodeWithTag("dynamic-publish-submit").performClick()
        rule.waitUntil(5_000) { completions == 1 && backs == 1 }
        assertEquals(1, service.published.size)
    }

    @Test fun imageReorderAndRemovalReachPublisherInVisibleOrder() {
        val model = show()
        rule.runOnIdle { model.addImages((1..3).map { n -> DynamicPublishingImageSource {
            DynamicImageUploadInput(byteArrayOf(n.toByte()), "image/png")
        } }) }
        rule.onNodeWithTag("dynamic-publish-image-options-3").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-publish-move-earlier-3").performClick()
        rule.onNodeWithTag("dynamic-publish-image-options-1").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-publish-remove-1").performClick()
        rule.onNodeWithTag("dynamic-publish-image-1").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-comment-input").performScrollTo().performTextInput("ordered")
        rule.onNodeWithTag("dynamic-publish-submit").performClick()
        rule.waitUntil(5_000) { service.published.size == 1 }
        assertEquals(listOf(3, 2), service.published.single().images.map { (it as PublishingUiImage).number })
    }

    @Test fun relayShowsSourceHasNoImagesAndAllowsEmptyComment() {
        show(17, "A guide source")
        rule.onNodeWithTag("dynamic-publish-source").assertIsDisplayed()
        rule.onNodeWithText("A guide source").assertIsDisplayed()
        rule.onNodeWithTag("dynamic-publish-images").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-publish-visibility-OnlyMe").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-publish-submit").performClick()
        rule.waitUntil(5_000) { completions == 1 }
        assertEquals(17, service.relayed.single().postId)
        assertEquals(DynamicVisibility.OnlyMe, service.relayed.single().visibility)
        assertEquals("", service.relayed.single().contentHtml)
        assertTrue(service.published.isEmpty())
    }

    @Test fun mentionAndEmojiKeepIdentityThroughNativeEditor() {
        show()
        rule.onNodeWithTag("dynamic-publish-mention").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Fixture member").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Fixture member").performClick()
        rule.onNodeWithTag("dynamic-publish-emoji").performClick()
        rule.onNodeWithContentDescription("Emoji 1").performClick()
        rule.onNodeWithTag("dynamic-publish-submit").performClick()
        rule.waitUntil(5_000) { service.published.size == 1 }
        val draft = service.published.single()
        assertEquals(listOf(DynamicCommentMention("fixture", "Fixture member")), draft.mentions)
        assertTrue(draft.contentHtml.contains("class=\"at-text\""))
        assertTrue(draft.contentHtml.contains("class=\"at-emo\""))
    }

    @Test fun clearingProtectedContentRemovesRelaySourceAndDraftFromScreen() {
        val model = show(17, "Protected source")
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("Protected draft")
        rule.runOnIdle { model.clearProtectedContent(DynamicPublishingError.AuthenticationRequired) }
        rule.onNodeWithText("Protected source").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-comment-input").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-publish-submit").assertIsNotEnabled()
        rule.onNodeWithTag("dynamic-publish-error").assertIsDisplayed()
        assertTrue(service.relayed.isEmpty())
    }

    @Test fun resizingKeepsDraftVisibilityAndImageQueueWithoutSending() {
        val model = show()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("kept across widths")
        rule.onNodeWithTag("dynamic-publish-visibility-MutualFollowers").performScrollTo().performClick()
        rule.runOnIdle { model.addImages(listOf(DynamicPublishingImageSource {
            error("resize must never read a selected image")
        })) }
        listOf(600, 839, 840).forEach { value ->
            rule.runOnIdle { width.value = value }
            rule.onNodeWithTag("dynamic-comment-input").performScrollTo().assertTextContains("kept across widths")
            rule.onNodeWithTag("dynamic-publish-visibility-MutualFollowers").performScrollTo().assertIsSelected()
            rule.onNodeWithTag("dynamic-publish-image-1").performScrollTo().assertIsDisplayed()
        }
        assertTrue(service.published.isEmpty())
    }

    @Test fun publishingAt599() = publishingAt(599)
    @Test fun publishingAt600() = publishingAt(600)
    @Test fun publishingAt839() = publishingAt(839)
    @Test fun publishingAt840() = publishingAt(840)

    private fun publishingAt(value: Int) {
        width.value = value
        show()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("width $value")
        rule.onNodeWithTag("dynamic-publish-visibility-MutualFollowers").performScrollTo().performClick()
        if (InstrumentationRegistry.getArguments().getString("publishingScreenshots") == "true") {
            rule.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { instrumentation.targetContext.cacheDir.resolve("publishing-$value.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
        rule.onNodeWithTag("dynamic-publish-submit").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { completions == 1 && backs == 1 }
        assertEquals(DynamicVisibility.MutualFollowers, service.published.single().visibility)
    }

    private fun show(postId: Int? = null, title: String? = null): DynamicPublishingViewModel {
        val model = DynamicPublishingViewModel(service, service, service, postId, title)
        owner.viewModelStore.put("dynamic-publishing-${System.identityHashCode(service)}-$postId", model)
        rule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalDensity provides Density(1f),
                LocalDynamicCommentEmojiLoader provides { null }) {
                MaterialTheme { Box(Modifier.width(width.value.dp).height(1000.dp)) {
                    RisingStonesDynamicPublishingScreen(service, service, { backs++ },
                        imageUploadService = service, relayPostId = postId, relayPostTitle = title,
                        onPublished = { completions++ })
                } }
            }
        }
        return model
    }
}

internal data class PublishingUiImage(val number: Int) : DynamicUploadedImage
internal class PublishingUiService : DynamicActionService by UiActions(), DynamicPublishingService,
    DynamicImageUploadService, DynamicPublishingImageUploadService {
    override val canUploadImages = false
    override val canAttemptImageUpload = true
    var failPublish = false
    var gate: CompletableDeferred<Unit>? = null
    val published = mutableListOf<DynamicPublishDraft>()
    val relayed = mutableListOf<DynamicPostRelayDraft>()
    override suspend fun fetchMentionCandidates(scope: DynamicActionScope, query: DynamicListQuery) =
        DynamicPage(listOf(DynamicCommentMention("fixture", "Fixture member")), query.page, false)
    override suspend fun publish(scope: DynamicActionScope, draft: DynamicPublishDraft) {
        if (failPublish) throw DynamicException.Network
        published += draft
        gate?.await()
    }
    override suspend fun relayPost(scope: DynamicActionScope, draft: DynamicPostRelayDraft) { relayed += draft }
    override suspend fun uploadPublishingImage(scope: DynamicActionScope, input: DynamicImageUploadInput) =
        PublishingUiImage(input.copyBytes().single().toInt())
    override suspend fun uploadCommentImage(scope: DynamicActionScope, input: DynamicImageUploadInput): DynamicUploadedImage =
        error("publishing must use the publishing image channel")
}
