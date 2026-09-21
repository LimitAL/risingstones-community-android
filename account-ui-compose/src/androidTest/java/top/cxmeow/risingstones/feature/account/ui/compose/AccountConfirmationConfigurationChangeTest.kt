package top.cxmeow.risingstones.feature.account.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import top.cxmeow.risingstones.feature.account.domain.RisingStonesDailySignInResult
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel

class AccountConfirmationConfigurationChangeTest {
    private val compose = createAndroidComposeRule<AccountConfirmationTestActivity>()
    @get:Rule val rules: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() = AccountConfigurationFixture.reset()
        })
        .around(compose)

    @Test
    fun recreationKeepsConfirmationWithoutWritingAndConfirmedActionSubmitsOnce() {
        val service = AccountConfigurationFixture.service
        waitFor("Daily sign-in")
        compose.onNodeWithText("Daily sign-in").performClick()
        waitFor("This will complete today’s sign-in for the current character.")
        compose.activityRule.scenario.recreate()
        waitFor("This will complete today’s sign-in for the current character.")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("This will complete today’s sign-in for the current character.")
            .assertDoesNotExist()
        assertEquals(0, service.verifySignInCalls)

        compose.onNodeWithText("Daily sign-in").performClick()
        compose.onNode(hasText("Sign in") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { service.verifySignInCalls == 1 }
        compose.activityRule.scenario.recreate()
        assertEquals(1, service.verifySignInCalls)
        service.signInGate.complete(Unit)
        waitFor("Fixture signed in")
        waitFor("Signed in")
        assertEquals(1, service.verifySignInCalls)
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

class AccountConfirmationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountViewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    RisingStonesAccountViewModel(AccountConfigurationFixture.service)
                }
            },
        )["account", RisingStonesAccountViewModel::class.java]
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    RisingStonesAccountScreen(
                        viewModel = accountViewModel,
                        sessionDisplayName = "Fixture",
                        canDailySignIn = false,
                        onManageSession = {},
                        onNavigateBack = {},
                    )
                }
            }
        }
    }
}

private object AccountConfigurationFixture {
    lateinit var service: DeferredFirstActionFixture
        private set

    fun reset() {
        service = DeferredFirstActionFixture()
    }
}

private class DeferredFirstActionFixture : FirstActionFixture() {
    val signInGate = CompletableDeferred<Unit>()

    override suspend fun verifyDailySignIn(): RisingStonesDailySignInResult {
        verifySignInCalls += 1
        signInGate.await()
        return RisingStonesDailySignInResult("Fixture signed in", false, 1, 1, null, null)
    }
}
