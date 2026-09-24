package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class DynamicRichTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emojiOnlyCommentHasAnAccessibleOfficialImage() {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDynamicCommentEmojiLoader provides { image }) {
                DynamicRichText("<p><img class='at-emo' src='${dynamicEmojiUrl(46)}'></p>")
            }
        } }
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Emoji 46").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Emoji 46").assertIsDisplayed()
    }
}
