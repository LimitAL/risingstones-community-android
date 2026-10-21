package top.cxmeow.risingstones.feature.personaldata.data

import org.junit.Assert.*
import org.junit.Test

class FishCatalogIntegrityTest {
    private fun decode(rows: String) = PersonalDataCatalogJsonDecoder().decode(
        fishDocument = """{"schemaVersion":1,"content":{"fish":$rows}}""".encodeToByteArray(),
        savageDocument = null, glamourDocument = null,
    ).fish
    @Test fun duplicateIdsCannotSilentlyChooseADenominator() {
        assertTrue(decode("""[{"itemId":1,"name":"A","patch":"2"},{"itemId":1,"name":"B","patch":"2"}]""").isEmpty())
    }
    @Test fun missingIdOrNonObjectCannotShrinkCatalogIntoApparentlyCompleteSubset() {
        for (row in listOf("""{"name":"B","patch":"2"}""", "null", "12")) {
            assertTrue(decode("""[{"itemId":1,"name":"A","patch":"2"},$row]""").isEmpty())
        }
    }
    @Test fun validCatalogKeepsEveryRowAndZeroIconCompatibility() {
        val fish=decode("""[{"itemId":1,"name":"A","patch":"2"},{"itemId":2,"name":"B","patch":"3"}]""")
        assertEquals(setOf(1,2),fish.keys);assertEquals(0,fish.getValue(1).iconId)
    }
}
