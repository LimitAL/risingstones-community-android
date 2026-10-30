package top.cxmeow.risingstones.feature.dynamic.presentation

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentMention

/** Pure editor projection: no credentials, clients, scopes, API fixtures or publishing requests. */
class DynamicMentionNotificationIdentityTest {
    private val old = DynamicCommentMention("synthetic-a", "Old Name")
    private val renamed = old.copy(characterName = "新名🙂<&")
    private val other = DynamicCommentMention("synthetic-b", old.characterName)
    private fun DynamicCommentEditorState.add(m: DynamicCommentMention) = insert("@${m.characterName} ", m)
    private fun outputs(s: DynamicCommentEditorState) = listOf(s.prepareComment(), s.preparePublishingContent())

    @Test fun renamedSameUuidNotifiesOnceWithoutChangingEitherVisibleSpan() {
        val s = DynamicCommentEditorState().add(old).insert("between ").add(renamed).add(old)
        for (r in outputs(s)) {
            assertEquals(listOf(old), r.mentions)
            assertEquals(3, Regex("class=\"at-text\"").findAll(r.html).count())
            assertTrue(r.html.contains("synthetic-a#Old Name"))
            assertTrue(r.html.contains("synthetic-a#新名🙂&lt;&amp;"))
            assertTrue(r.html.contains("@新名🙂&lt;&amp;"))
        }
    }
    @Test fun differentUuidsWithSameNameRemainSeparateAndUseTextOrder() {
        val s = DynamicCommentEditorState().add(other).add(renamed).add(old)
        for (r in outputs(s.copy(mentions = s.mentions.reversed()))) {
            assertEquals(listOf(other, renamed), r.mentions)
            assertEquals(3, Regex("class=\"at-text\"").findAll(r.html).count())
        }
    }
    @Test fun staleAndOverlappingMetadataNeverReserveAnIdentityBeforeItsVisibleSpan() {
        val s = DynamicCommentEditorState().add(renamed).add(other)
        val invalid = DynamicCommentMentionRange(-1, 200, old)
        val overlapping = s.mentions.first().copy(mention = other)
        for (r in outputs(s.copy(mentions = listOf(invalid, s.mentions.first(), overlapping, s.mentions.last())))) {
            assertEquals(listOf(renamed, other), r.mentions)
            assertEquals(2, Regex("class=\"at-text\"").findAll(r.html).count())
        }
    }
    @Test fun ordinaryAtTextAndRemovedTokensDoNotCreateNotifications() {
        val s = DynamicCommentEditorState("ordinary @Old Name ", 19, 19).add(old).add(renamed).add(other)
        val removed = s.removeMention(old.uuid)
        for (r in outputs(removed)) {
            assertEquals(listOf(other), r.mentions)
            assertTrue(r.html.contains("ordinary @Old Name"))
            assertFalse(r.html.contains("synthetic-a#"))
        }
    }
    @Test fun repeatedIdenticalMentionsAndParagraphsKeepOneRecipient() {
        val s = DynamicCommentEditorState().add(old).insert("\n\n").add(other).insert("\n").add(old)
        for (r in outputs(s)) {
            assertEquals(listOf(old, other), r.mentions)
            assertEquals(3, Regex("<p>").findAll(r.html).count())
            assertEquals(3, Regex("class=\"at-text\"").findAll(r.html).count())
        }
    }
    @Test fun firstVisibleNameWinsAndChangingSelectionDoesNotChangeProjection() {
        val s = DynamicCommentEditorState().add(renamed).add(old)
        for (r in outputs(s.copy(selectionStart = 0, selectionEnd = s.text.length))) assertEquals(listOf(renamed), r.mentions)
        assertEquals(outputs(s), outputs(s.copy(selectionStart = 1, selectionEnd = 1)))
    }
}
