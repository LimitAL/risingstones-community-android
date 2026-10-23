package top.cxmeow.risingstones.feature.forum.data

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment as Segment

class OfficialForumCommentInlineFlowTest {
    @Test fun lineBreaksRemainAroundLinksAndEmojiWithoutDroppingInlineText() {
        val html = "<b>First</b><br><a href='https://example.test/mock?a=1&amp;b=2'><strong>link</strong></a><div>[emo2] <em>Tail</em></div>"
        assertEquals(listOf(
            Segment.Text("First\n"), Segment.Link("link", "https://example.test/mock?a=1&b=2"),
            Segment.Text("\n"), Segment.Emoji(2), Segment.Text(" Tail"),
        ), OfficialForumHtml.richSegments(html))
        assertEquals("First\nlink\n[emo2] Tail", OfficialForumHtml.plainText(html))
    }

    @Test fun spacesBetweenInlineAtomsAndImageDimensionsRemainIntact() {
        assertEquals(listOf(Segment.Text("Before "), Segment.Link("link", "https://example.test/x"), Segment.Text(" after")),
            OfficialForumHtml.richSegments(" Before <a href='https://example.test/x'>link</a> after "))
        assertEquals(listOf(Segment.Text("A"), Segment.Image("https://cdn.test/x.png", 120, 80), Segment.Text("B")),
            OfficialForumHtml.richSegments("<span>A</span><img src='https://cdn.test/x.png' width='120' height='80'><span>B</span>"))
    }

    @Test fun unsafeUrlsStillCannotBecomeLinksOrImages() {
        for (url in listOf("javascript:alert(1)", "data:text/html,unsafe", "file:///mock", "astria://mock")) {
            val segments = OfficialForumHtml.richSegments("<a href='$url'><b>Keep</b><br>text</a><img src='$url' onerror='ignored()'>")
            assertTrue(segments.none { it is Segment.Link || it is Segment.Image })
            assertEquals("Keep\ntext", segments.filterIsInstance<Segment.Text>().joinToString("") { it.value })
        }
    }

    @Test fun structuredParagraphsDoNotReinterpretExplicitBreaksAsSourceFormatting() {
        val blocks = OfficialForumHtml.postBlocks("<p>A<br><a href='https://example.test/mock'>link</a></p><p>B</p>", emptyList())
            .filterIsInstance<top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock.Paragraph>()
        assertEquals(2, blocks.size)
        assertEquals(listOf(Segment.Text("A\n"), Segment.Link("link", "https://example.test/mock")), blocks.first().segments)
        val pre = OfficialForumHtml.postBlocks("<pre>A\nB</pre>", emptyList()).single()
            as top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock.Paragraph
        assertEquals(listOf(Segment.Text("A\nB")), pre.segments)
        val images = OfficialForumHtml.postBlocks("<img src='https://cdn.test/a.png'>\n\n<img src='https://cdn.test/b.png'>", emptyList())
        assertTrue(images.all { it is top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock.Image })
        assertEquals(2, images.size)
    }

    @Test fun escapedMarkupRemainsLiteralAndDoesNotAcquireHtmlCapabilities() {
        val segments = OfficialForumHtml.richSegments("&lt;br&gt; &lt;img src='https://cdn.test/x.png'&gt;")
        assertEquals(listOf(Segment.Text("<br> <img src='https://cdn.test/x.png'>")), segments)
        assertTrue(OfficialForumHtml.richSegments("<script>not executed</script>").all { it is Segment.Text })
    }
}
