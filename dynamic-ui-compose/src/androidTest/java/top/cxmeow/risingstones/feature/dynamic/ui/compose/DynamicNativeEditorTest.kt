package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.text.style.ImageSpan
import android.view.inputmethod.EditorInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentMention
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicCommentEditorState
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicCommentMentionRange

/** Generated image fixtures exercise native editing without downloading official assets. */
class DynamicNativeEditorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun officialEmojiUsesBitmapSpanAndImeDeletesItAsOneVisibleObject() {
        val state = mutableStateOf(DynamicCommentEditorState("A[emo46]B", 8, 8))
        show(state)
        rule.waitUntil(5_000) { bitmapCount() == 1 }
        native { editor ->
            assertEquals(8, editor.selectionStart)
            assertTrue(requireNotNull(editor.onCreateInputConnection(EditorInfo())).deleteSurroundingText(1, 0))
            assertEquals("AB", editor.text.toString())
        }
        rule.runOnIdle { assertEquals("AB", state.value.text) }
    }

    @Test fun emojiSyntaxInsideMentionIsNeverReplacedWithAnImage() {
        val name = "Fixture[emo1]"
        val text = "@$name [emo2]"
        val state = mutableStateOf(DynamicCommentEditorState(text, text.length, text.length,
            listOf(DynamicCommentMentionRange(0, name.length + 1, DynamicCommentMention("fixture-id", name)))))
        show(state)
        rule.waitUntil(5_000) { bitmapCount() == 1 }
        native { editor ->
            val editable = requireNotNull(editor.text)
            val span = editable.getSpans(0, editable.length, ImageSpan::class.java).single()
            assertEquals(name.length + 2, editable.getSpanStart(span))
        }
    }

    @Test fun accessibilityTypingUpdatesTheSameNativeEditableAndSelection() {
        val state = mutableStateOf(DynamicCommentEditorState())
        show(state)
        rule.onNodeWithTag("dynamic-comment-input").performTextInput("Fixture🙂")
        rule.runOnIdle {
            assertEquals("Fixture🙂", state.value.text)
            assertEquals(state.value.text.length, state.value.selectionStart)
        }
        native { assertEquals(state.value.text, it.text.toString()) }
    }

    private fun show(state: androidx.compose.runtime.MutableState<DynamicCommentEditorState>) {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        rule.setContent {
            CompositionLocalProvider(LocalDynamicCommentEmojiLoader provides { image }) {
                MaterialTheme {
                    DynamicNativeEditor(state.value, true) { text, start, end, _ ->
                        state.value = state.value.copy(text = text, selectionStart = start, selectionEnd = end)
                    }
                }
            }
        }
    }

    private fun bitmapCount(): Int {
        var count = 0
        native { editor -> count = requireNotNull(editor.text).getSpans(0, editor.length(), ImageSpan::class.java)
            .count { it.drawable is BitmapDrawable } }
        return count
    }

    private fun native(check: (DynamicNativeCommentEditText) -> Unit) {
        onView(withTagValue(equalTo("dynamic-comment-native-input"))).check { view, error ->
            if (error != null) throw error
            check(view as DynamicNativeCommentEditText)
        }
    }
}
