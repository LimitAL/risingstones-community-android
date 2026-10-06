package top.cxmeow.risingstones.feature.glamour.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlamourDetailContractTest {
    @Test
    fun legacyPositionalConstructorsAndCopyKeepNewMetadataOptional() {
        val tag = GlamourDetailTag(8, "Fixture tag", false, 2, "Fixture category", 1, 2)
        val author = GlamourAuthor(null, "—", "", "", null)
        val detail = GlamourDetail(
            42, "Fixture title", "", emptyList(), author, 0, 0, false, false, null,
            emptyList(), emptyList(), null, null, false, null, false, false, listOf(tag),
        ).copy(title = "Refreshed title")

        assertEquals("Refreshed title", detail.title)
        assertEquals(listOf(tag), detail.tags)
        assertEquals(1, detail.tags.single().categorySort)
        assertEquals(2, detail.tags.single().tagSort)
        assertNull(detail.tags.single().categoryCode)
        assertTrue(detail.jobs.isEmpty())
        assertTrue(detail.genderIds.isEmpty())
    }
}
