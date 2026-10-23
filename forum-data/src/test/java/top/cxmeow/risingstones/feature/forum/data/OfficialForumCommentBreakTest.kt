package top.cxmeow.risingstones.feature.forum.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment

@RunWith(Parameterized::class)
class OfficialForumCommentBreakTest(private val html: String, private val expected: String) {
    @Test fun plainTextFollowsExplicitAndStructuralBreaks() {
        assertEquals(expected, OfficialForumHtml.plainText(html))
    }
    @Test fun displayedTextSegmentsFollowTheSameBreaks() {
        val text = OfficialForumHtml.richSegments(html).joinToString("") {
            when (it) {
                is OfficialForumRichTextSegment.Text -> it.value
                is OfficialForumRichTextSegment.Link -> it.text
                is OfficialForumRichTextSegment.Emoji -> "[emo${it.number}]"
                is OfficialForumRichTextSegment.Image -> ""
            }
        }
        assertEquals(expected, text)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}")
        fun cases(): List<Array<String>> = listOf(
            arrayOf("First<br>Second", "First\nSecond"),
            arrayOf("<DiV>First</dIv><DIV>Second</DIV>", "First\nSecond"),
            arrayOf("<div>First<div>Second</div>Third</div>", "First\nSecond\nThird"),
            arrayOf("<div><div>First</div></div><div>Second</div>", "First\nSecond"),
            arrayOf("First<BR/><br />Second", "First\n\nSecond"),
            arrayOf("<div>First</div><div><br></div><div>Second</div>", "First\n\nSecond"),
            arrayOf("<div>First</div><div></div><div>&nbsp;</div><div>Second</div>", "First\nSecond"),
            arrayOf("<p>First</p>\n<p>Second</p>", "First\n\nSecond"),
            arrayOf("<p>First<br>Second</p><p>Third</p>", "First\nSecond\n\nThird"),
            arrayOf("<p>First</p><p>&nbsp;</p>", "First"),
            arrayOf("<br><div> First </div><br>", "First"),
            arrayOf("First\nSecond", "First\nSecond"),
            arrayOf("First\r\n\r\nSecond", "First\n\nSecond"),
            arrayOf("<p>One\nword</p>", "One word"),
            arrayOf("<b>First</b><br><i>Second</i>", "First\nSecond"),
            arrayOf("<p>A</p><p><br></p><p>B</p>", "A\n\n\nB"),
            arrayOf("<div><br></div>", ""),
            arrayOf("<pre>First\nSecond</pre>", "First\nSecond"),
            arrayOf("<pre>First\n\nSecond</pre>", "First\n\nSecond"),
            arrayOf("First&#13;&#10;Second", "First\nSecond"),
            arrayOf("First&#10;&#10;Second", "First\n\nSecond"),
            arrayOf("First<Br class='soft-break'>Second", "First\nSecond"),
            arrayOf("\nFirst\n\nSecond\n", "First\n\nSecond"),
            arrayOf("<p>记录7</p><div>高摔1血拍死</div><div>剧终</div>", "记录7\n\n高摔1血拍死\n剧终"),
        )
    }
}
