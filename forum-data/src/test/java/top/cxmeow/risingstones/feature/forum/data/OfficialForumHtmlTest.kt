package top.cxmeow.risingstones.feature.forum.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment

class OfficialForumHtmlTest {
    @Test
    fun exposesRichSegmentsThroughPublicClientFacade() {
        val segments = OfficialForumHtmlParser.richSegments("hello [emo2]")

        assertEquals(2, segments.size)
        assertTrue(segments.last() is OfficialForumRichTextSegment.Emoji)
    }

    @Test
    fun keepsInlineImagesInSourceOrderWithLinksAndEmoji() {
        val segments = OfficialForumHtml.richSegments(
            "before <a href=\"https://example.test/post/1\">link</a><img src=\"https://cdn.test/a.png\" width=\"120\">[emo2] after",
        )

        assertTrue(segments[0] is OfficialForumRichTextSegment.Text)
        assertTrue(segments[1] is OfficialForumRichTextSegment.Link)
        assertTrue(segments[2] is OfficialForumRichTextSegment.Image)
        assertEquals(120, (segments[2] as OfficialForumRichTextSegment.Image).width)
        assertTrue(segments[3] is OfficialForumRichTextSegment.Emoji)
    }

    @Test
    fun parsesStandaloneMediaAndStructuredBlocksWithoutAppendingImages() {
        val blocks = OfficialForumHtml.postBlocks(
            """
            <p>开头</p><img src="https://cdn.test/a.png" style="width: 640px; height: 360px">
            <hr><table><tr><th>名称</th><td>内容</td></tr></table>
            <details open><summary>更多</summary><p>折叠正文</p></details>
            <iframe src="https://player.bilibili.com/player.html?bvid=BV1xx"></iframe>
            """.trimIndent(),
            emptyList(),
        )

        assertTrue(blocks.any { it is OfficialForumPostBodyBlock.Image })
        assertTrue(blocks.any { it is OfficialForumPostBodyBlock.Divider })
        assertTrue(blocks.any { it is OfficialForumPostBodyBlock.Table })
        assertTrue(blocks.any { it is OfficialForumPostBodyBlock.Disclosure })
        assertTrue(blocks.any { it is OfficialForumPostBodyBlock.VideoEmbed })
        val image = blocks.filterIsInstance<OfficialForumPostBodyBlock.Image>().single()
        assertEquals(640, image.width)
        assertEquals(360, image.height)
    }
}
