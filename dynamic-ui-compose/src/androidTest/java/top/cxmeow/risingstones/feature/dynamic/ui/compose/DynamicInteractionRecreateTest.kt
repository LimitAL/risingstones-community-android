package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel

class DynamicInteractionRecreateTest {
    @get:Rule val rule = createAndroidComposeRule<DynamicRecreateTestActivity>()

    @Test fun activityRecreateKeepsDraftWithoutSendingAndSubmitsOnlyOnce() {
        DynamicRecreateTestActivity.actions.comments.clear()
        waitFor("Feed item")
        rule.onNodeWithText("Feed item").performClick()
        waitFor("Detail body")
        rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("survives")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag("dynamic-comment-input").assertTextContains("survives")
        assertEquals(0, DynamicRecreateTestActivity.actions.comments.size)
        rule.onNodeWithTag("dynamic-submit-comment").performClick()
        rule.waitUntil(5_000) { DynamicRecreateTestActivity.actions.comments.size == 1 }
        assertEquals(1, DynamicRecreateTestActivity.actions.comments.size)
    }

    @Test fun recreationDuringSubmissionDoesNotResendAndHandlesCompletion() {
        val actions = DynamicRecreateTestActivity.actions
        actions.comments.clear()
        val gate = CompletableDeferred<Unit>()
        actions.commentGate = gate
        try {
            waitFor("Feed item")
            rule.onNodeWithText("Feed item").performClick()
            waitFor("Detail body")
            rule.onNodeWithTag("dynamic-comment").performScrollTo().performClick()
            rule.onNodeWithTag("dynamic-comment-input").performTextInput("send once")
            rule.onNodeWithTag("dynamic-submit-comment").performClick()
            rule.waitUntil(5_000) { actions.comments.size == 1 }
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            rule.onNodeWithTag("dynamic-submit-comment").assertIsNotEnabled()
            assertEquals(1, actions.comments.size)
            gate.complete(Unit)
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("dynamic-comment-input").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, actions.comments.size)
        } finally {
            gate.complete(Unit)
            actions.commentGate = null
        }
    }

    private fun waitFor(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

class DynamicRecreateTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val reading: DynamicViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DynamicViewModel(UiReadService) as T
            })
            MaterialTheme {
                RisingStonesDynamicActionProvider(actions) {
                    Box(Modifier.fillMaxSize()) { RisingStonesDynamicScreen(reading, {}) }
                }
            }
        }
    }

    companion object { internal val actions = UiActions() }
}
