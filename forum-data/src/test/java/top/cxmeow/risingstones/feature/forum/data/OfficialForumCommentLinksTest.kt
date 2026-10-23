package top.cxmeow.risingstones.feature.forum.data

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment as Segment

class OfficialForumCommentLinksTest {
    private fun links(html: String) = OfficialForumHtml.richSegments(html).filterIsInstance<Segment.Link>()
    private fun display(html: String) = OfficialForumHtml.richSegments(html).joinToString("") {
        when (it) { is Segment.Text -> it.value; is Segment.Link -> it.text; is Segment.Emoji -> "[emo${it.number}]"; is Segment.Image -> "" }
    }
    @Test fun bareUrlsBecomeLinksWithoutChangingVisibleText() {
        val samples = listOf("Text https://example.test/mock", "中文https://example.test/mock，尾", "繁體（https://example.test/mock）", "HTTPS://example.test/mock", "https://example.test/mock?a=1&amp;b=2")
        for (html in samples) {
            assertEquals(html, 1, links(html).size)
            assertEquals(OfficialForumHtml.plainText(html), display(html))
        }
    }
    @Test fun punctuationAndBalancedParenthesesStayOutsideTheLink() {
        for ((html, url) in listOf("https://example.test/a." to "https://example.test/a", "(see https://example.test/a)" to "https://example.test/a", "https://example.test/wiki/A_(B)!" to "https://example.test/wiki/A_(B)", "https://example.test/x?cd=AB==，继续" to "https://example.test/x?cd=AB==")) {
            assertEquals(html, listOf(url), links(html).map { it.url }); assertEquals(html, display(html))
        }
    }
    @Test fun urlsCannotSwallowBreaksEmojiOrAdjacentLinks() {
        val html = "https://example.test/a<br>https://example.test/b[emo3] 尾"
        assertEquals(listOf("https://example.test/a", "https://example.test/b"), links(html).map { it.url })
        assertEquals("https://example.test/a\nhttps://example.test/b[emo3] 尾", display(html))
        assertTrue(OfficialForumHtml.richSegments(html).any { it == Segment.Emoji(3) })
    }
    @Test fun escapedAnchorsDecodeOnceAndKeepTheirOwnLabel() {
        val html = "前&lt;A HREF=&quot;https://example.test/a?x=1&amp;y=2&quot;&gt;标签 &amp;amp; 内容&lt;/A&gt;后"
        assertEquals(listOf(Segment.Link("标签 &amp; 内容", "https://example.test/a?x=1&y=2")), links(html))
        assertEquals("前标签 &amp; 内容后", display(html))
    }
    @Test fun rawAnchorsAreNotAutoLinkedAgain() {
        val html = "<a href='https://example.test/target'>https://example.test/label</a> https://example.test/after"
        assertEquals(listOf(Segment.Link("https://example.test/label", "https://example.test/target"), Segment.Link("https://example.test/after", "https://example.test/after")), links(html))
        assertEquals("https://example.test/label https://example.test/after", display(html))
    }
    @Test fun doubleEscapedAnchorsStayLiteral() {
        val html = "&amp;lt;a href='https://example.test/a'&amp;gt;https://example.test/label&amp;lt;/a&amp;gt;"
        assertTrue(links(html).isEmpty()); assertEquals(OfficialForumHtml.plainText(html), display(html))
    }
    @Test fun unsafeMalformedAndNonWebValuesCannotBecomeLinks() {
        for (value in listOf("javascript:alert(1)", "data:text/html,hello", "file:///mock", "astria://mock", "https://", "https:///mock", "https://bad%host/x", "javascript:https://example.test/x")) {
            assertTrue(value, links(value).isEmpty())
            val escaped = "&lt;a href='$value'&gt;保留&lt;/a&gt;"
            assertTrue(escaped, links(escaped).isEmpty())
        }
    }
    @Test fun nestedEscapedMarkupDoesNotCreateNestedLinksOrDuplicateText() {
        val html = "&lt;a href='https://example.test/outer'&gt;A&lt;a href='https://example.test/inner'&gt;B&lt;/a&gt;C&lt;/a&gt;"
        assertTrue(links(html).size <= 1)
        assertEquals(1, Regex("A").findAll(display(html)).count())
        assertEquals(1, Regex("B").findAll(display(html)).count())
    }
    @Test fun numericQuoteEntitiesAndMixedCaseAnchorsDecodeOnce() {
        val html = "&lt;A HrEf=&#39;https://example.test/a?x=1&amp;amp;y=2&#39;&gt;Label&lt;/A&gt;"
        assertEquals(listOf(Segment.Link("Label", "https://example.test/a?x=1&amp;y=2")), links(html))
    }
    @Test fun plainNewlinesAndEscapedLabelsNeverJoinAdjacentUrls() {
        val html = "https://example.test/a\n\nhttps://example.test/b"
        assertEquals(listOf("https://example.test/a", "https://example.test/b"), links(html).map { it.url })
        assertEquals(html, display(html))
        assertEquals(listOf(Segment.Link("https://example.test/label", "https://example.test/target")), links("&lt;a href='https://example.test/target'&gt;https://example.test/label&lt;/a&gt;"))
    }
    @Test fun unsafeEncodedSchemesAndControlCharactersStayInert() {
        for (href in listOf("jav&#97;script:alert(1)", "d&#97;ta:text/html,hello", "https://example.test/a&#10;b", "https://example.test/a%0Axx%00")) {
            val html = "&lt;a href='$href'&gt;Label&lt;/a&gt;"
            if (href.endsWith("%00")) assertEquals(href, links(html).single().url) // URI-escaped text is not decoded again.
            else assertTrue(href, links(html).isEmpty())
        }
    }
    @Test fun nestedRawAnchorsFlattenWithoutDuplicatingOrNestingLinks() {
        val html = "<a href='https://example.test/outer'>A<a href='https://example.test/inner'>B</a>C</a>"
        assertTrue(links(html).isEmpty()); assertEquals("ABC", display(html))
    }
    @Test fun existingRawAnchorEntityAndNonWebBehaviorRemainUnchanged() {
        assertEquals(listOf(Segment.Link("A &amp; B", "https://example.test/a?x=1&amp;y=2")), links("<a href='https://example.test/a?x=1&amp;amp;y=2'>A &amp;amp; B</a>"))
        assertEquals("Label", display("<a href='javascript:alert(1)'>Label</a>"))
        assertTrue(links("<a href='javascript:alert(1)'>Label</a>").isEmpty())
    }
    @Test fun malformedEscapedAnchorsAreNotPartiallyRestored() {
        for (html in listOf("&lt;a href='https://example.test/x'&gt;Label", "&lt;a href=https://example.test/x&gt;Label&lt;/a&gt;", "&lt;a href='https:///x'&gt;Label&lt;/a&gt;")) {
            assertTrue(html, links(html).isEmpty())
            assertEquals(html, OfficialForumHtml.plainText(html), display(html))
        }
    }

}
