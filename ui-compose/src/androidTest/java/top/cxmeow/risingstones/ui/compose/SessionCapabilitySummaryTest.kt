package top.cxmeow.risingstones.ui.compose

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCredentialSource
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

@RunWith(AndroidJUnit4::class)
class SessionCapabilitySummaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val activeSession = RisingStonesSessionState.Active(
        source = RisingStonesCredentialSource.WebCookie,
        capabilities = setOf(RisingStonesCapability.AccountRead),
        displayName = "PRIVATE_DISPLAY_NAME",
    )

    @Test
    fun compactShowsOnlyCapabilitiesVerifiedForCurrentSession() {
        showAtWidth(400.dp)
        assertCapabilityStates()
    }

    @Test
    fun mediumShowsOnlyCapabilitiesVerifiedForCurrentSession() {
        showAtWidth(700.dp)
        assertCapabilityStates()
    }

    @Test
    fun expandedShowsOnlyCapabilitiesVerifiedForCurrentSession() {
        showAtWidth(900.dp)
        assertCapabilityStates()
    }

    @Test
    fun showsCredentialHandlingDisclosureBeforeLoginOrReconnect() {
        showAtWidth(400.dp)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.rising_stones_credential_handling))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun copyButtonWritesOnlySanitizedCapabilityBooleans() {
        showAtWidth(400.dp)

        composeRule
            .onNodeWithTag("rising-stones-copy-capability-report")
            .performScrollTo()
            .performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val copiedText = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        val expectedReport = """
            AccountRead=true
            DailySignIn=false
            ForumWrite=false
            ForumImageUpload=false
            RecruitmentAuthenticated=false
            RecruitmentWrite=false
            GlamourAuthenticated=false
            PersonalData=false
        """.trimIndent()

        assertEquals(expectedReport, sanitizedCapabilityReport(activeSession))
        assertEquals(expectedReport, copiedText)
        assertEquals(displayedCapabilities.size, copiedText?.lines()?.size)
        assertTrue(copiedText.orEmpty().lines().all { line ->
            line.matches(Regex("[A-Za-z]+=(true|false)"))
        })
        assertFalse(copiedText.orEmpty().contains(activeSession.displayName.orEmpty()))
        assertFalse(copiedText.orEmpty().contains("Cookie", ignoreCase = true))
        assertFalse(copiedText.orEmpty().contains("User-Agent", ignoreCase = true))
        assertFalse(copiedText.orEmpty().contains("WebCookie"))

        composeRule
            .onNodeWithTag("rising-stones-copy-capability-report")
            .assertTextContains(
                context.getString(R.string.rising_stones_capability_report_copied),
            )
    }

    private fun showAtWidth(width: Dp) {
        assertEquals(RisingStonesCapability.entries.size, displayedCapabilities.size)
        assertEquals(RisingStonesCapability.entries.toSet(), displayedCapabilities.toSet())
        composeRule.setContent {
            Box(
                Modifier
                    .width(width)
                    .height(800.dp),
            ) {
                MaterialTheme {
                    SessionContent(
                        sessionState = activeSession,
                        loginError = null,
                        onLogin = {},
                        onSignOut = {},
                    )
                }
            }
        }
    }

    private fun assertCapabilityStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verifiedText = context.getString(R.string.rising_stones_capability_verified)
        val notVerifiedText = context.getString(R.string.rising_stones_capability_not_verified)

        composeRule
            .onNodeWithTag("rising-stones-capability-AccountRead")
            .assertTextContains(verifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-DailySignIn")
            .assertTextContains(notVerifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-ForumWrite")
            .assertTextContains(notVerifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-ForumImageUpload")
            .assertTextContains(notVerifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-RecruitmentAuthenticated")
            .assertTextContains(notVerifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-RecruitmentWrite")
            .assertTextContains(notVerifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-GlamourAuthenticated")
            .assertTextContains(notVerifiedText)
        composeRule
            .onNodeWithTag("rising-stones-capability-PersonalData")
            .performScrollTo()
            .assertTextContains(notVerifiedText)
    }
}
