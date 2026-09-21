package top.cxmeow.risingstones.feature.forum.presentation

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentEmojiNumbers
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentMention

class OfficialForumCommentEditorTest {
    @Test fun utf16CursorNeverSplitsSurrogatePairAndReversedSelectionReplacesWholeCharacter() {
        val original = OfficialForumCommentEditorState("A😀B")
        val cursor = original.edit(original.text, 2, 2)
        assertEquals(1, cursor.selectionStart)
        assertEquals("Ax😀B", cursor.insert("x").text)

        val selected = original.edit(original.text, 3, 1).insert("中")
        assertEquals("A中B", selected.text)
        assertEquals(2, selected.selectionStart)
        assertEquals(2, selected.selectionEnd)
        val clamped = original.edit(original.text, -9, 99).insert("全部")
        assertEquals("全部", clamped.text)
    }

    @Test fun insertedMentionUsesUtf16RangeAndReplacesOnlySelection() {
        val mention = OfficialForumCommentMention("fixture-one", "😀名字")
        val state = OfficialForumCommentEditorState("前旧后", 1, 2).insert("@${mention.name} ", mention)
        assertEquals("前@😀名字 后", state.text)
        assertEquals(7, state.selectionStart)
        assertEquals(listOf(OfficialForumCommentMentionRange(1, 6, mention)), state.mentions)
        assertEquals(listOf(mention), state.prepareComment().mentions)
    }

    @Test fun explicitEditsBeforeAndAfterMentionMoveRangeWithoutLosingIdentity() {
        val initial = oneMention("pre @Name tail", 4, "Name")
        val before = initial.edit("😀pre @Name tail", 2, 2, OfficialForumCommentTextChange(0, 0))
        assertEquals(6, before.mentions.single().start)
        assertEquals(11, before.mentions.single().end)
        val after = before.edit("😀pre @Name tail!", 17, 17,
            OfficialForumCommentTextChange(before.text.length, before.text.length))
        assertEquals(before.mentions, after.mentions)
        val removedPrefix = after.edit("@Name tail!", 0, 0, OfficialForumCommentTextChange(0, 6))
        assertEquals(0, removedPrefix.mentions.single().start)
        assertEquals(listOf(initial.mentions.single().mention), removedPrefix.prepareComment().mentions)
    }

    @Test fun insertAtMentionBoundariesPreservesIdentityButInsideEditInvalidatesIt() {
        val initial = oneMention("@Name", 0, "Name")
        val atStart = initial.copy(selectionStart = 0, selectionEnd = 0).insert("!")
        assertEquals(1, atStart.mentions.single().start)
        val atEnd = initial.copy(selectionStart = 5, selectionEnd = 5).insert("!")
        assertEquals(initial.mentions, atEnd.mentions)
        val inside = initial.copy(selectionStart = 2, selectionEnd = 2).insert("x")
        assertTrue(inside.mentions.isEmpty())
        assertEquals("<p>@Nxame</p>", inside.prepareComment().html)
    }

    @Test fun deletingOrReplacingPartOfMentionTurnsRemainingTextIntoPlainText() {
        val initial = oneMention("@Name hello", 0, "Name")
        val deleted = initial.edit("@Nme hello", 2, 2, OfficialForumCommentTextChange(2, 3))
        assertTrue(deleted.prepareComment().mentions.isEmpty())
        assertEquals("<p>@Nme hello</p>", deleted.prepareComment().html)
        val replaced = initial.copy(selectionStart = 1, selectionEnd = 4).insert("Ada")
        assertEquals("@Adae hello", replaced.text)
        assertTrue(replaced.prepareComment().mentions.isEmpty())
    }

    @Test fun pastingIdenticalTextOverMentionCancelsIdentityWhileSelectionOnlyKeepsIt() {
        val initial = oneMention("@Name hello", 0, "Name")
        val selectionOnly = initial.edit(initial.text, 5, 0)
        assertEquals(initial.mentions, selectionOnly.mentions)
        val pasted = selectionOnly.insert("@Name")
        assertEquals(initial.text, pasted.text)
        assertTrue(pasted.mentions.isEmpty())
        assertEquals("<p>@Name hello</p>", pasted.prepareComment().html)
    }

    @Test fun ambiguousPlainTextChangeCannotTransferIdentityBetweenSameNamedPeople() {
        val first = OfficialForumCommentMention("fixture-one", "Same")
        val second = OfficialForumCommentMention("fixture-two", "Same")
        val initial = OfficialForumCommentEditorState("@Same @Same ", mentions = listOf(
            OfficialForumCommentMentionRange(0, 5, first), OfficialForumCommentMentionRange(6, 11, second)))
        val unknownEdit = initial.edit("@Same ", 0, 0)
        assertTrue(unknownEdit.mentions.isEmpty())
        assertEquals("<p>@Same</p>", unknownEdit.prepareComment().html)
        val knownDelete = initial.edit("@Same ", 0, 0, OfficialForumCommentTextChange(0, 6))
        assertEquals(listOf(second), knownDelete.prepareComment().mentions)
    }

    @Test fun typingOrPastingAtNameAloneNeverCreatesNotificationMetadata() {
        val state = OfficialForumCommentEditorState().insert("@Name ").insert("@Name")
        assertTrue(state.mentions.isEmpty())
        assertEquals("<p>@Name @Name</p>", state.prepareComment().html)
        val changed = oneMention("@Name", 0, "Name").edit("@Name!", 6, 6)
        assertTrue(changed.mentions.isEmpty())
    }

    @Test fun repeatedUuidAndNameDeduplicatesMetadataButSameUuidDifferentNameIsRetained() {
        val original = OfficialForumCommentMention("fixture-one", "Name")
        val alias = original.copy(name = "Alias")
        val state = OfficialForumCommentEditorState().insert("@Name ", original)
            .insert("@Name ", original).insert("@Alias ", alias)
        val prepared = state.prepareComment()
        assertEquals(listOf(original, alias), prepared.mentions)
        assertEquals(3, Regex("class=\"at-text\"").findAll(prepared.html).count())
    }

    @Test fun htmlAndAttributeCharactersStayEscapedInPlainTextAndMention() {
        val mention = OfficialForumCommentMention("fixture\"'&<>", "\"'&<>")
        val state = OfficialForumCommentEditorState().insert("<script>\"'& ")
            .insert("@${mention.name} ", mention)
        assertEquals(
            "<p>&lt;script&gt;&quot;&#39;&amp; <span class=\"at-text\" " +
                "data-uuid=\"fixture&quot;&#39;&amp;&lt;&gt;#&quot;&#39;&amp;&lt;&gt;\" " +
                "contenteditable=\"false\">@&quot;&#39;&amp;&lt;&gt;</span></p>",
            state.prepareComment().html,
        )
        assertEquals(listOf(mention), state.prepareComment().mentions)
    }

    @Test fun allFortySixOfficialEmojiRenderButOutOfRangeAndLookalikeTokensStayLiteral() {
        assertEquals((1..46).toList(), OfficialForumCommentEmojiNumbers.toList())
        for (number in 1..46) {
            assertEquals("<p><span class=\"at-emo\">[emo$number]</span></p>",
                OfficialForumCommentEditorState("[emo$number]").prepareComment().html)
        }
        val invalid = "[emo0] [emo47] [emo01] [emo-1] [EMO1] [emo460]"
        assertEquals("<p>$invalid</p>", OfficialForumCommentEditorState(invalid).prepareComment().html)
    }

    @Test fun emojiTokenInsideSelectedCharacterNameIsLiteralAndDoesNotChangeIdentity() {
        val mention = OfficialForumCommentMention("fixture-one", "Name[emo1]")
        val state = OfficialForumCommentEditorState().insert("@${mention.name} ", mention).insert("[emo46]")
        assertEquals("<p><span class=\"at-text\" data-uuid=\"fixture-one#Name[emo1]\" " +
            "contenteditable=\"false\">@Name[emo1]</span> <span class=\"at-emo\">[emo46]</span></p>",
            state.prepareComment().html)
        assertEquals(listOf(mention), state.prepareComment().mentions)
    }

    @Test fun invalidAndOverlappingRangesNeverAddExtraIdentityOrBreakRendering() {
        val name = OfficialForumCommentMention("fixture-one", "Name")
        val alias = name.copy(uuid = "fixture-two")
        val state = OfficialForumCommentEditorState("@Name tail", mentions = listOf(
            OfficialForumCommentMentionRange(-1, 4, name),
            OfficialForumCommentMentionRange(0, 5, name),
            OfficialForumCommentMentionRange(0, 5, alias),
            OfficialForumCommentMentionRange(6, 99, alias),
            OfficialForumCommentMentionRange(6, 5, alias),
            OfficialForumCommentMentionRange(6, 10, alias),
        ))
        val prepared = state.prepareComment()
        assertEquals(listOf(name), prepared.mentions)
        assertEquals(1, Regex("class=\"at-text\"").findAll(prepared.html).count())
        assertTrue(prepared.html.endsWith("</span> tail</p>"))
    }

    @Test fun forbiddenIdentitySeparatorsAndControlsRemainPlainText() {
        val invalid = listOf(
            OfficialForumCommentMention("fixture#one", "Name"),
            OfficialForumCommentMention("fixture-one", "Name#Other"),
            OfficialForumCommentMention("", "Name"),
            OfficialForumCommentMention("fixture\n", "Name"),
            OfficialForumCommentMention("fixture-one", "Name\u2028Other"),
            OfficialForumCommentMention("fixture-one", "Name\u2029Other"),
        )
        invalid.forEach { mention ->
            val state = OfficialForumCommentEditorState().insert("@${mention.name} ", mention)
            assertFalse(mention.isEncodable())
            assertTrue(state.prepareComment().mentions.isEmpty())
            assertFalse(state.prepareComment().html.contains("at-text"))
        }
    }

    @Test fun blankLinesAndOuterWhitespaceAreTrimmedWithoutLosingContainedMention() {
        val initial = oneMention(" \t\r\n  @Name  \r\n\n <x> \r [emo1] \n", 6, "Name")
        val prepared = initial.prepareComment()
        assertEquals(listOf(initial.mentions.single().mention), prepared.mentions)
        assertEquals("<p><span class=\"at-text\" data-uuid=\"fixture-one#Name\" " +
            "contenteditable=\"false\">@Name</span></p><p>&lt;x&gt;</p>" +
            "<p><span class=\"at-emo\">[emo1]</span></p>", prepared.html)
        assertEquals("", OfficialForumCommentEditorState(" \r\n\t ").prepareComment().html)
    }

    @Test fun trailingSpaceInsideSelectedCharacterNameIsPreservedWithoutChangingIdentity() {
        val mention = OfficialForumCommentMention("fixture-one", "Player ")
        val selected = OfficialForumCommentEditorState().insert("@${mention.name} ", mention).prepareComment()
        assertEquals(listOf(mention), selected.mentions)
        assertEquals("<p><span class=\"at-text\" data-uuid=\"fixture-one#Player \" " +
            "contenteditable=\"false\">@Player </span></p>", selected.html)
        assertEquals("<p>@Player</p>", OfficialForumCommentEditorState("@Player  ").prepareComment().html)
    }

    @Test fun inconsistentExplicitChangeDiscardsIdentityInsteadOfTrustingWrongRange() {
        val initial = oneMention("@Name tail", 0, "Name")
        listOf(OfficialForumCommentTextChange(-1, 0), OfficialForumCommentTextChange(9, 2),
            OfficialForumCommentTextChange(0, 99), OfficialForumCommentTextChange(6, 7)).forEach { change ->
            val edited = initial.edit("!@Name tail", -1, 99, change)
            assertTrue(edited.mentions.isEmpty())
            assertEquals(0, edited.selectionStart)
            assertEquals(edited.text.length, edited.selectionEnd)
        }
    }

    private fun oneMention(text: String, start: Int, name: String) = OfficialForumCommentEditorState(
        text = text,
        mentions = listOf(OfficialForumCommentMentionRange(start, start + 1 + name.length,
            OfficialForumCommentMention("fixture-one", name))),
    )
}
