package top.cxmeow.risingstones.feature.account.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel

class AccountGuildNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optionalGuildEntryInvokesHostAndDisappearsWhenCapabilityIsRevoked() {
        val service = FirstActionFixture()
        val model = RisingStonesAccountViewModel(service)
        var granted by mutableStateOf(false)
        var opened = 0
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.rising_stones_account_my_guild)
        compose.setContent {
            MaterialTheme {
                RisingStonesAccountGuildNavigation(if (granted) { { opened++ } } else null) {
                    RisingStonesAccountScreen(model, "Synthetic account", false, {}, {})
                }
            }
        }
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle { granted = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(1, opened)
        assertEquals(0, service.verifySignInCalls)
        assertEquals(0, service.verifyRewardCalls)
        compose.runOnIdle { granted = false }
        compose.onNodeWithText(label).assertDoesNotExist()
    }
}
