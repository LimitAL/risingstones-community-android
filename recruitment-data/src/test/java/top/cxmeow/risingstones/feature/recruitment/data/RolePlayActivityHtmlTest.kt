package top.cxmeow.risingstones.feature.recruitment.data

import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivityBodyBlock

class RolePlayActivityHtmlTest {
    @Test
    fun linkedImagesKeepTheirHttpsDestinationAndTextFormattingAcrossTheImage() {
        val parsed = RolePlayActivityHtml.parse("""<p><a href="https://ff14risingstones.web.sdo.com/?a=1&amp;b=2"><b>Before<img src="https://static.web.sdo.com/a.png" alt="Linked">After</b></a></p>""")
        val image = parsed.blocks.filterIsInstance<RolePlayActivityBodyBlock.Image>().single()
        assertEquals("https://ff14risingstones.web.sdo.com/?a=1&b=2", image.linkUrl)
        assertEquals("Linked", image.alternativeText)
        val html = parsed.blocks.filterIsInstance<RolePlayActivityBodyBlock.Html>()
        assertTrue(html.first().contentHtml.endsWith("<b>Before</b></a></p>"))
        assertTrue(html.last().contentHtml.endsWith("<b>After</b></a></p>"))
        assertFalse(parsed.hasUnsupportedContent)
        val unsafe = RolePlayActivityHtml.parse("""<a href="javascript:bad()"><img src="https://static.web.sdo.com/a.png"></a>""")
        assertNull(unsafe.blocks.filterIsInstance<RolePlayActivityBodyBlock.Image>().single().linkUrl)
    }

    @Test
    fun linksAndBasicFormattingSurviveWhileUnsafeAttributesAndUrlsDoNot() {
        val parsed = RolePlayActivityHtml.parse("""<p onclick="bad()" style="color:#123456;background-image:url(https://invalid.example/a)"><b>Bold</b><i>Italic</i><u>Under</u><s>Strike</s><a href="https://ff14risingstones.web.sdo.com/?a=1&amp;b=2" target="_blank">Link</a><a href="javascript&#58;bad()">Unsafe link</a></p>""")
        val html = (parsed.blocks.single() as RolePlayActivityBodyBlock.Html).contentHtml
        assertTrue(html.contains("<b>Bold</b><i>Italic</i><u>Under</u><strike>Strike</strike>"))
        assertTrue(html.contains("href=\"https://ff14risingstones.web.sdo.com/?a=1&amp;b=2\""))
        assertTrue(html.contains("style=\"color:#123456\""))
        assertTrue(html.contains("<span>Unsafe link</span>"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("background-image"))
        assertFalse(html.contains("javascript"))
        assertTrue(parsed.hasUnsupportedContent)
    }

    @Test
    fun repeatedImagesKeepTheirPlaceAndInsecureOrCredentialUrlsAreRejected() {
        val parsed = RolePlayActivityHtml.parse("""<p>One<img src=https://static.web.sdo.com/a.png>Two<img src='https://static.web.sdo.com/a.png'>Three<img src='http://static.web.sdo.com/b.png' alt='Fallback &amp; text'><img src='https://fixture-user@static.web.sdo.com/c.png'><img src='//static.web.sdo.com/d.png'></p>""")
        val images = parsed.blocks.filterIsInstance<RolePlayActivityBodyBlock.Image>()
        assertEquals(listOf("https://static.web.sdo.com/a.png", "https://static.web.sdo.com/a.png"), images.map { it.url })
        assertEquals(listOf("<p>One</p>", "<p>Two</p>", "<p>ThreeFallback &amp; text</p>"), parsed.blocks.filterIsInstance<RolePlayActivityBodyBlock.Html>().map { it.contentHtml })
        assertTrue(parsed.hasUnsupportedContent)
        for (url in listOf("javascript:bad()", "https://static.web.sdo.com:8443/a", "https://static.web.sdo.com/\na", "https://static.web.sdo.com\\@invalid.example/a")) {
            assertNull(RolePlayActivityHtml.httpsUrl(url))
        }
    }

    @Test
    fun unsupportedEmbedsAndTablesAreExplicitAndScriptContentsAreNeverRendered() {
        val parsed = RolePlayActivityHtml.parse("""<p>Before</p><script>unsafe text<img src='https://static.web.sdo.com/script.png'></script><iframe src='https://invalid.example/'></iframe><table><tr><td>Cell A</td><td>Cell B</td></tr></table><p>After</p>""")
        assertTrue(parsed.hasUnsupportedContent)
        assertTrue(parsed.blocks.none { it is RolePlayActivityBodyBlock.Image })
        val html = parsed.blocks.filterIsInstance<RolePlayActivityBodyBlock.Html>().joinToString { it.contentHtml }
        assertTrue(html.contains("Before"))
        assertTrue(html.contains("Cell A"))
        assertTrue(html.contains("Cell B"))
        assertTrue(html.contains("After"))
        assertFalse(html.contains("unsafe text"))
        assertFalse(html.contains("iframe"))
        assertFalse(html.contains("script"))
    }

    @Test
    fun malformedTrailingMarkupRemainsLiteralAndFormattingContinuesAcrossImages() {
        val parsed = RolePlayActivityHtml.parse("<ul><li><em>First<img src='https://static.web.sdo.com/a.png'>Last</em></li></ul> <a href='javascript:bad()'")
        val html = parsed.blocks.filterIsInstance<RolePlayActivityBodyBlock.Html>()
        assertEquals("<ul><li><em>First</em></li></ul>", html[0].contentHtml)
        assertEquals("<ul><li><em>Last</em></li></ul> &lt;a href='javascript:bad()'", html[1].contentHtml)
    }
}
