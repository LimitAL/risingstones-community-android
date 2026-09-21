package top.cxmeow.risingstones.feature.account.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountActionVerificationService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountDashboard
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesDailySignInResult
import top.cxmeow.risingstones.feature.account.domain.RisingStonesReward
import top.cxmeow.risingstones.feature.account.domain.RisingStonesRewardStatus
import top.cxmeow.risingstones.feature.account.domain.RisingStonesSignInSummary
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel

class AccountFirstActionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactFirstSignInNeedsConfirmation() = verifyFirstSignInAt(599)
    @Test fun mediumFirstSignInNeedsConfirmation() = verifyFirstSignInAt(600)
    @Test fun upperMediumFirstSignInNeedsConfirmation() = verifyFirstSignInAt(839)
    @Test fun expandedFirstSignInNeedsConfirmation() = verifyFirstSignInAt(840)

    @Test
    fun firstRewardClaimNeedsConfirmationAndCancelDoesNotWrite() {
        val service = FirstActionFixture()
        show(service, 599)
        waitFor("Claim")

        compose.onNodeWithText("Claim").performClick()
        waitFor("Claim this month’s “Fixture reward”?")
        assertEquals(0, service.verifyRewardCalls)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, service.verifyRewardCalls)

        compose.onNodeWithText("Claim").performClick()
        dialogButton("Claim").performClick()
        compose.waitUntil { service.verifyRewardCalls == 1 }
        waitFor("Fixture reward claimed")
        waitFor("Received")
        assertEquals(1, service.verifyRewardCalls)
    }

    private fun verifyFirstSignInAt(width: Int) {
        val service = FirstActionFixture()
        show(service, width)
        waitFor("Daily sign-in")

        compose.onNodeWithText("Daily sign-in").performClick()
        waitFor("This will complete today’s sign-in for the current character.")
        assertEquals(0, service.verifySignInCalls)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, service.verifySignInCalls)

        compose.onNodeWithText("Daily sign-in").performClick()
        dialogButton("Sign in").performClick()
        compose.waitUntil { service.verifySignInCalls == 1 }
        waitFor("Fixture signed in")
        waitFor("Signed in")
        waitUntilMissing("This will complete today’s sign-in for the current character.")
        assertEquals(1, service.verifySignInCalls)
        saveScreenshot(width)
    }

    @Test
    fun revokedEligibilityAndViewModelReplacementDiscardPendingConfirmation() {
        val service = MutableEligibilityFixture()
        val firstViewModel = RisingStonesAccountViewModel(service)
        val activeViewModel = mutableStateOf(firstViewModel)
        compose.setContent {
            MaterialTheme {
                RisingStonesAccountScreen(
                    viewModel = activeViewModel.value,
                    sessionDisplayName = "Fixture",
                    canDailySignIn = false,
                    onManageSession = {},
                    onNavigateBack = {},
                )
            }
        }
        waitFor("Daily sign-in")

        compose.onNodeWithText("Daily sign-in").performClick()
        waitFor("This will complete today’s sign-in for the current character.")
        compose.runOnIdle { service.eligible = false }
        waitUntilMissing("This will complete today’s sign-in for the current character.")
        assertEquals(0, service.verifySignInCalls)

        compose.runOnIdle { service.eligible = true }
        waitFor("Daily sign-in")
        compose.onNodeWithText("Daily sign-in").performClick()
        waitFor("This will complete today’s sign-in for the current character.")
        val replacementService = FirstActionFixture()
        compose.runOnIdle {
            activeViewModel.value = RisingStonesAccountViewModel(replacementService)
        }
        waitUntilMissing("This will complete today’s sign-in for the current character.")
        assertEquals(0, service.verifySignInCalls)
        assertEquals(0, replacementService.verifySignInCalls)
    }

    private fun show(service: FirstActionFixture, width: Int) {
        val viewModel = RisingStonesAccountViewModel(service)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width.dp).height(1_000.dp)) {
                    RisingStonesAccountScreen(
                        viewModel = viewModel,
                        sessionDisplayName = "Fixture",
                        canDailySignIn = false,
                        onManageSession = {},
                        onNavigateBack = {},
                    )
                }
            }
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun waitUntilMissing(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    private fun dialogButton(text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(isDialog()) and hasClickAction(),
    )

    private fun saveScreenshot(width: Int) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // The platform Dialog window can still be fading out after Compose has removed its nodes.
        automation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        val destination = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("account-first-action-$width.png")
        destination.outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}

private class MutableEligibilityFixture : FirstActionFixture() {
    var eligible by mutableStateOf(true)
    override val canVerifyDailySignIn: Boolean get() = eligible
}

internal open class FirstActionFixture : RisingStonesAccountService, RisingStonesAccountActionVerificationService {
    var verifySignInCalls = 0
    var verifyRewardCalls = 0
    private var signedIn = false
    private var rewardClaimed = false

    override val canVerifyDailySignIn: Boolean = true

    override suspend fun fetchSignInSummary() = RisingStonesSignInSummary(null, emptyList(), emptyList())

    override suspend fun fetchDashboard() = RisingStonesAccountDashboard(
        character = null,
        houseRemainDayText = null,
        signInCount = if (signedIn) 1 else 0,
        signInLogs = emptyList(),
        rewards = listOf(
            RisingStonesReward(
                id = 7,
                description = "Fixture reward",
                itemName = null,
                requiredDays = 1,
                status = if (rewardClaimed) RisingStonesRewardStatus.Received else RisingStonesRewardStatus.Claimable,
            ),
        ),
    )

    override suspend fun signIn() = error("The verified capability is intentionally unavailable")
    override suspend fun claimReward(id: Int) = error("The verified capability is intentionally unavailable")

    override open suspend fun verifyDailySignIn(): RisingStonesDailySignInResult {
        verifySignInCalls += 1
        signedIn = true
        return RisingStonesDailySignInResult("Fixture signed in", false, 1, 1, null, null)
    }

    override suspend fun verifyClaimReward(id: Int): String {
        verifyRewardCalls += 1
        rewardClaimed = true
        return "Fixture reward claimed"
    }
}
