package top.cxmeow.risingstones.ui.compose

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginCaptureProtectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    @After
    fun clearSecureFlag() {
        composeRule.runOnUiThread {
            composeRule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test
    fun protectsOnlyWhileLoginWebViewIsVisible() {
        val enabled = mutableStateOf(false)
        composeRule.setContent {
            RisingStonesLoginCaptureProtection(enabled = enabled.value)
        }
        composeRule.runOnIdle {
            assertFalse(composeRule.activity.isSecure())
        }

        composeRule.runOnUiThread {
            enabled.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(composeRule.activity.isSecure())
        }

        composeRule.runOnUiThread {
            enabled.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(composeRule.activity.isSecure())
        }
    }

    @Test
    fun doesNotClearFlagOwnedByHostActivity() {
        composeRule.runOnUiThread {
            composeRule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val enabled = mutableStateOf(true)
        composeRule.setContent {
            RisingStonesLoginCaptureProtection(enabled = enabled.value)
        }
        composeRule.runOnIdle {
            assertTrue(composeRule.activity.isSecure())
        }

        composeRule.runOnUiThread {
            enabled.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(composeRule.activity.isSecure())
        }
    }

    private fun ComponentActivity.isSecure(): Boolean =
        window.attributes.flags.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
}
