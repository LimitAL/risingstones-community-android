package top.cxmeow.risingstones.feature.dynamic.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentMention

class DynamicCommentEditorTest {
    @Test fun preparesOnlyRenderedMentionIdentityAndOfficialEmoji() {
        val mention = DynamicCommentMention("uuid", "Member")
        val state = DynamicCommentEditorState().insert("@Member", mention).insert(" [emo46]")
        val (html, mentions) = state.prepareComment()
        assertTrue(html.contains("data-uuid=\"uuid#Member\""))
        assertTrue(html.contains("https://static.web.sdo.com/jijiamobile/pic/ff14/2023ffstone/emo46.png"))
        assertEquals(listOf(mention), mentions)
    }

    @Test fun editOffsetsUnaffectedMentionsAndInvalidatesTouchedIdentity() {
        val mention = DynamicCommentMention("uuid", "Member")
        val selected = DynamicCommentEditorState("A@Member Z", 9, 9,
            listOf(DynamicCommentMentionRange(1, 8, mention)))
        val prefixed = selected.edit("xxA@Member Z", 11, 11, DynamicCommentTextChange(0, 0))
        assertEquals(3, prefixed.mentions.single().start)
        val touched = prefixed.edit("xxA@MEmber Z", 6, 6, DynamicCommentTextChange(5, 6))
        assertTrue(touched.mentions.isEmpty())
    }

    @Test fun utf16SelectionNeverSplitsSurrogateAndBlankLinesFollowOfficialParagraphRules() {
        val state = DynamicCommentEditorState("A😀B", 2, 2).insert("Z")
        assertEquals("AZ😀B", state.text)
        assertEquals("<p>one</p><p>two</p>", DynamicCommentEditorState(" one \n\n two ").prepareComment().html)
    }

    @Test fun escapesHtmlAndDoesNotInterpretEmojiInsideMentionName() {
        val mention = DynamicCommentMention("id", "Name[emo1]")
        val state = DynamicCommentEditorState().insert("@Name[emo1] ", mention).insert("<&")
        val prepared = state.prepareComment()
        assertEquals(0, Regex("emo1.png").findAll(prepared.html).count())
        assertTrue(prepared.html.contains("&lt;&amp;"))
        assertEquals(listOf(mention), prepared.mentions)
    }
}
