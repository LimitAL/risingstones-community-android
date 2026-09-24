package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DynamicPublishingRecreateTest {
    @get:Rule val rule = createAndroidComposeRule<DynamicPublishingTestActivity>()
    @Before fun reset() {
        DynamicPublishingTestActivity.service.published.clear()
        DynamicPublishingTestActivity.completions = 0
        DynamicPublishingTestActivity.backs = 0
    }

    @Test fun realActivityRecreateRetainsDraftAndVisibilityWithoutPublishing() {
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("retained publishing draft")
        rule.onNodeWithTag("dynamic-publish-visibility-OnlyMe").performScrollTo().performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("dynamic-comment-input").assertTextContains("retained publishing draft")
        rule.onNodeWithTag("dynamic-publish-visibility-OnlyMe").performScrollTo().assertIsSelected()
        assertEquals(0, DynamicPublishingTestActivity.service.published.size)
        rule.onNodeWithTag("dynamic-publish-submit").performClick()
        rule.waitUntil(5_000) { DynamicPublishingTestActivity.completions == 1 }
        assertEquals(1, DynamicPublishingTestActivity.backs)
    }

    @Test fun realActivityRecreateDuringPublishDoesNotResendOrLoseCompletion() {
        val service = DynamicPublishingTestActivity.service
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        try {
            rule.onNodeWithTag("dynamic-comment-input").performTextInput("send exactly once")
            rule.onNodeWithTag("dynamic-publish-submit").performClick()
            rule.waitUntil(5_000) { service.published.size == 1 }
            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag("dynamic-publish-submit").assertIsNotEnabled()
            assertEquals(1, service.published.size)
            gate.complete(Unit)
            rule.waitUntil(5_000) { DynamicPublishingTestActivity.completions == 1 }
            assertEquals(1, DynamicPublishingTestActivity.backs)
            assertEquals(1, service.published.size)
        } finally { gate.complete(Unit); service.gate = null }
    }
}

class DynamicPublishingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalDynamicCommentEmojiLoader provides { null }) {
                MaterialTheme { RisingStonesDynamicPublishingScreen(service, service, { backs++ },
                    imageUploadService = service, onPublished = { completions++ }) }
            }
        }
    }
    companion object {
        internal val service = PublishingUiService()
        internal var completions = 0
        internal var backs = 0
    }
}
