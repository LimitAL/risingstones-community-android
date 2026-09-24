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
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicPublishingError
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicRecruitmentRelayViewModel

class DynamicRecruitmentRelayScreenTest {
    @get:Rule val rule = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val service = RecruitmentRelayUiService()
    private val width = mutableStateOf(599)
    private var backs = 0
    private var completions = 0
    @After fun clear() { rule.runOnIdle { owner.viewModelStore.clear() } }

    @Test fun onlyVisibilityIsAuthoredAndBackDoesNotRelay() {
        show()
        rule.onNodeWithText("Recruitment source").assertIsDisplayed()
        rule.onNodeWithTag("dynamic-comment-input").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-publish-images").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-OnlyMe").performClick()
        rule.onNodeWithTag("dynamic-recruitment-relay-back").performClick()
        assertEquals(1, backs)
        assertTrue(service.calls.isEmpty())
    }

    @Test fun failedRelayPreservesChoiceAndAllowsOnlyExplicitRetry() {
        service.fail = true
        show()
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-MutualFollowers").performClick()
        rule.onNodeWithTag("dynamic-recruitment-relay-submit").performClick()
        rule.onNodeWithTag("dynamic-recruitment-relay-error").assertIsDisplayed()
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-MutualFollowers").assertIsSelected()
        assertEquals(0, completions)
        assertEquals(1, service.calls.size)
        rule.runOnIdle { service.fail = false }
        rule.onNodeWithTag("dynamic-recruitment-relay-submit").performClick()
        rule.waitUntil(5_000) { completions == 1 && backs == 1 }
        assertEquals(2, service.calls.size)
    }

    @Test fun revokedConfirmationRemovesSourceAndDisablesSubmission() {
        val model = show()
        rule.runOnIdle { model.clearProtectedContent(DynamicPublishingError.AuthenticationRequired) }
        rule.onNodeWithText("Recruitment source").assertDoesNotExist()
        rule.onNodeWithTag("dynamic-recruitment-relay-submit").assertIsNotEnabled()
        rule.onNodeWithTag("dynamic-recruitment-relay-error").assertIsDisplayed()
        assertTrue(service.calls.isEmpty())
    }

    @Test fun resizingKeepsVisibilityAndWaitsForExplicitSubmission() {
        show()
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-OnlyMe").performClick()
        for (value in listOf(600, 839, 840)) {
            rule.runOnIdle { width.value = value }
            rule.onNodeWithTag("dynamic-recruitment-relay-visibility-OnlyMe").assertIsSelected()
            rule.onNodeWithText("Recruitment source").assertIsDisplayed()
        }
        assertTrue(service.calls.isEmpty())
    }

    @Test fun recruitmentRelayAt599() = relayAt(599)
    @Test fun recruitmentRelayAt600() = relayAt(600)
    @Test fun recruitmentRelayAt839() = relayAt(839)
    @Test fun recruitmentRelayAt840() = relayAt(840)

    private fun relayAt(value: Int) {
        width.value = value
        show()
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-OnlyMe").performClick()
        if (InstrumentationRegistry.getArguments().getString("recruitmentRelayScreenshots") == "true") {
            rule.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { instrumentation.targetContext.cacheDir.resolve("recruitment-relay-$value.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
        rule.onNodeWithTag("dynamic-recruitment-relay-submit").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { completions == 1 && backs == 1 }
        assertEquals(DynamicRecruitmentRelayDraft(17, DynamicOrigin.DutyRecruitment, DynamicVisibility.OnlyMe), service.calls.single())
    }

    private fun show(): DynamicRecruitmentRelayViewModel {
        val model = DynamicRecruitmentRelayViewModel(service, service, 17, DynamicOrigin.DutyRecruitment, "Recruitment source")
        owner.viewModelStore.put("dynamic-recruitment-relay-${System.identityHashCode(service)}-DutyRecruitment-17", model)
        rule.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalDensity provides Density(1f)) {
            MaterialTheme { Box(Modifier.width(width.value.dp).height(1000.dp)) {
                RisingStonesDynamicRecruitmentRelayScreen(service, service, 17, DynamicOrigin.DutyRecruitment,
                    "Recruitment source", { backs++ }, onPublished = { completions++ })
            } }
        } }
        return model
    }
}

internal class RecruitmentRelayUiService : DynamicActionService by UiActions(), DynamicRecruitmentRelayService {
    val calls = mutableListOf<DynamicRecruitmentRelayDraft>()
    var fail = false
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun relayRecruitment(scope: DynamicActionScope, draft: DynamicRecruitmentRelayDraft) {
        calls += draft
        if (fail) throw DynamicException.Network
        gate?.await()
    }
}
