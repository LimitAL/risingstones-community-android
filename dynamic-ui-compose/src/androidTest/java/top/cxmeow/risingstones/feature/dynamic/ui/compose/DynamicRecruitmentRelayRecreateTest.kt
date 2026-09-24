package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicOrigin

class DynamicRecruitmentRelayRecreateTest {
    @get:Rule val rule = createAndroidComposeRule<DynamicRecruitmentRelayTestActivity>()
    @Before fun reset() {
        DynamicRecruitmentRelayTestActivity.service.calls.clear()
        DynamicRecruitmentRelayTestActivity.backs = 0
        DynamicRecruitmentRelayTestActivity.completions = 0
    }

    @Test fun activityRecreateKeepsScopeSelectionWithoutSending() {
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-OnlyMe").performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("dynamic-recruitment-relay-visibility-OnlyMe").assertIsSelected()
        assertEquals(0, DynamicRecruitmentRelayTestActivity.service.calls.size)
        rule.onNodeWithTag("dynamic-recruitment-relay-submit").performClick()
        rule.waitUntil(5_000) { DynamicRecruitmentRelayTestActivity.completions == 1 }
        assertEquals(1, DynamicRecruitmentRelayTestActivity.backs)
    }

    @Test fun activityRecreateDuringRelayRetainsOneRequestAndConsumesCompletionOnce() {
        val service = DynamicRecruitmentRelayTestActivity.service
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        try {
            rule.onNodeWithTag("dynamic-recruitment-relay-submit").performClick()
            rule.waitUntil(5_000) { service.calls.size == 1 }
            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag("dynamic-recruitment-relay-submit").assertIsNotEnabled()
            assertEquals(1, service.calls.size)
            gate.complete(Unit)
            rule.waitUntil(5_000) { DynamicRecruitmentRelayTestActivity.completions == 1 }
            assertEquals(1, DynamicRecruitmentRelayTestActivity.backs)
            assertEquals(1, service.calls.size)
        } finally { gate.complete(Unit); service.gate = null }
    }
}

class DynamicRecruitmentRelayTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            RisingStonesDynamicRecruitmentRelayScreen(service, service, 17, DynamicOrigin.GuildRecruitment,
                "Fixture recruitment", { backs++ }, onPublished = { completions++ })
        } }
    }
    companion object {
        internal val service = RecruitmentRelayUiService()
        internal var completions = 0
        internal var backs = 0
    }
}
