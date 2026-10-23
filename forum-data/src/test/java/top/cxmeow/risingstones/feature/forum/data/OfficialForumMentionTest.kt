package top.cxmeow.risingstones.feature.forum.data

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment as Segment

class OfficialForumMentionTest {
    private fun mentions(html: String) = OfficialForumHtml.richSegments(html).filterIsInstance<Segment.Mention>()
    @Test fun commentIdentityIsRetainedWithoutLookingUpTheDisplayName() {
        assertEquals(listOf(Segment.Text("你好"), Segment.Mention("10000011", "晨星", "@晨星"), Segment.Text(" 一起")),
            OfficialForumHtml.richSegments("<p>你好<span class='at-text' data-uuid='10000011#晨星'>@旧名</span>&nbsp;一起</p>"))
    }
    @Test fun nestedStyledPostMentionBecomesOneExplicitUser() {
        val html = "感谢<span class='mention' data-id='mock-user_12#泡泡鱼'><span><b>@</b>泡泡鱼</span></span> 的攻略"
        assertEquals(listOf(Segment.Mention("mock-user_12", "泡泡鱼", "@泡泡鱼")), mentions(html))
        assertEquals(3, OfficialForumHtml.richSegments(html).size)
    }
    @Test fun entitiesDecodeOnceWithoutTurningNamesIntoMarkup() {
        assertEquals(listOf(Segment.Mention("10000011", "A&lt;b&gt;", "@A&lt;b&gt;")), mentions("<SPAN CLASS='other at-text' DATA-UUID='10000011#A&amp;lt;b&amp;gt;'>ignored</SPAN>"))
    }
    @Test fun missingNameUsesExistingVisibleTextAndNeverGuessesIdentity() {
        assertEquals(listOf(Segment.Mention("opaque-id", "", "@Label")), mentions("<span class='at-text' data-uuid='opaque-id'><b>@Label</b></span>"))
        assertTrue(mentions("@Label <span class='at-text'>@Label</span>").isEmpty())
    }
    @Test fun invalidOrMissingIdentityAndUnclosedSpansRemainText() {
        for (id in listOf("", "#Name", "bad/id#Name", "javascript:1#Name", "bad%2Fid#Name", "bad&#10;id#Name", "bad id#Name")) {
            assertTrue(id, mentions("<span class='at-text' data-uuid='$id'>@Name</span>").isEmpty())
        }
        assertTrue(mentions("<span class='at-text' data-uuid='10000011#Name'>@Name").isEmpty())
    }
    @Test fun escapedSpansAndOrdinaryAtTextCannotCreateUserLinks() {
        for (html in listOf("@晨星", "<span style='color:red'>@晨星</span>", "&lt;span class='at-text' data-uuid='10000011#晨星'&gt;@晨星&lt;/span&gt;", "<span class='at-emo'>[emo9]</span>")) assertTrue(mentions(html).isEmpty())
    }
    @Test fun nestedIdentitiesUseOuterExplicitIdentityOnly() {
        assertEquals(listOf(Segment.Mention("outer", "Outer", "@Outer")), mentions("<span class='mention' data-id='outer#Outer'><span class='at-text' data-uuid='inner#Inner'>@Inner</span></span>"))
    }
    @Test fun sameNameWithDifferentIdsKeepsBothTargetsAndBreaks() {
        val html = "<span class='at-text' data-uuid='one#Same'>@Same</span><br><span class='mention' data-id='two#Same'>@Same</span>"
        assertEquals(listOf("one", "two"), mentions(html).map { it.userId })
        assertTrue(OfficialForumHtml.richSegments(html).contains(Segment.Text("\n")))
    }
    @Test fun existingAnchorOwnsItsBodyAndMentionCannotOpenAnEmbeddedUrl() {
        assertTrue(mentions("<a href='https://example.test/mock'><span class='at-text' data-uuid='one#Name'>@Name</span></a>").isEmpty())
        val segments = OfficialForumHtml.richSegments("<span class='at-text' data-uuid='one#Name'><a href='javascript:alert(1)'>@Name</a></span>")
        assertEquals(listOf(Segment.Mention("one", "Name", "@Name")), segments)
    }
    @Test fun attributesAndClassesMustBeExactAndQuoted() {
        for (html in listOf("<span class='not-at-text' data-uuid='one#Name'>@Name</span>", "<span class='at-text' x-data-uuid='one#Name'>@Name</span>", "<span class='at-text' data-uuid=one#Name>@Name</span>")) assertTrue(mentions(html).isEmpty())
    }
    @Test fun largeNumericAndOpaqueIdsAreNeverConvertedToInteger() {
        for (id in listOf("999999999999999999999999", "mock-user_12")) assertEquals(id, mentions("<span class='at-text' data-uuid='$id#Name'>@Name</span>").single().userId)
    }
    @Test fun structuredPostParagraphPreservesExplicitMentionIdentity() {
        val blocks = OfficialForumHtml.postBlocks("<p><span class='mention' data-id='one#Name'><b>@Name</b></span></p>", emptyList())
        val paragraph = blocks.single() as top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock.Paragraph
        assertEquals(listOf(Segment.Mention("one", "Name", "@Name")), paragraph.segments)
    }

}
