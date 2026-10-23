package top.cxmeow.risingstones.feature.forum.domain

import org.junit.Assert.*
import org.junit.Test

class OfficialForumPostAuthoringTest {
    private val part = OfficialForumPartFilter(8, "Mock", 0)
    @Test fun titleUsesTrimmedUtf16WithoutChangingInput() {
        assertEquals(32, OfficialForumPostRules.titleLength("  " + "😀".repeat(16) + "  "))
        assertEquals(33, OfficialForumPostRules.titleLength("😀".repeat(16) + "x"))
        assertFalse(OfficialForumPostRules.canSubmit(8, listOf(part), "😀".repeat(17), OfficialForumPostDocument.fromPlainText("Body")))
    }
    @Test fun plainTextDocumentPreservesBlankLinesAndNormalizesNewlines() {
        val doc = OfficialForumPostDocument.fromPlainText("A\r\n\r\nB\n")
        assertEquals(listOf("A", "", "B", ""), doc.paragraphs)
        assertEquals("A\n\nB\n", doc.plainText)
    }
    @Test fun serializationEscapesEveryTextParagraphAndSkipsBlankOnes() {
        val doc = OfficialForumPostDocument.fromPlainText("  A & <b> \n \n\"B\" 'C'\n[emo2]")
        assertEquals("<p>  A &amp; &lt;b&gt;</p><p>&quot;B&quot; &#39;C&#39;</p><p>[emo2]</p>", doc.toHtml())
    }
    @Test fun officialOnlyPartsAreNotPublishingChoices() {
        val all = listOf(part) + listOf(74,75,78,0).map { OfficialForumPartFilter(it,"Mock",0) }
        assertEquals(listOf(part), OfficialForumPostRules.postableParts(all))
        assertFalse(OfficialForumPostRules.canSubmit(75, all, "Title", OfficialForumPostDocument.fromPlainText("Body")))
    }
    @Test fun allThreeRequiredFieldsMustBeLoadedAndValid() {
        val doc = OfficialForumPostDocument.fromPlainText("Body")
        assertTrue(OfficialForumPostRules.canSubmit(8, listOf(part), "Title", doc))
        assertFalse(OfficialForumPostRules.canSubmit(null, listOf(part), "Title", doc))
        assertFalse(OfficialForumPostRules.canSubmit(8, emptyList(), "Title", doc))
        assertFalse(OfficialForumPostRules.canSubmit(8, listOf(part), " \n ", doc))
        assertFalse(OfficialForumPostRules.canSubmit(8, listOf(part), "Title", OfficialForumPostDocument.fromPlainText(" \n ")))
    }
}
