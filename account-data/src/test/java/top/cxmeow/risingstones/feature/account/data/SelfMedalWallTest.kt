package top.cxmeow.risingstones.feature.account.data
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
class SelfMedalWallTest {
    private fun wall(value:String)=Json.parseToJsonElement(value).jsonObject.selfMedalWall()
    @Test fun missingNullAndWrongShapeAreUnknownWhileArrayIsComplete() {
        listOf("{}","{\"achieveInfo\":null}","{\"achieveInfo\":{}}").forEach { assertNull(wall(it)) }
        val empty=wall("{\"achieveInfo\":[]}")!!;assertEquals(0,empty.sourceRows);assertEquals(0,empty.rejectedRows)
    }
    @Test fun exactIdsTypesAndRawDatesSurviveWithoutGuessingOrDroppingBadRowCounts() {
        val result=wall("""{"character_name":"Self","group_name":"Group","achieve_publish":0,"achieveInfo":[{"achieve_id":7,"achieve_name":"Medal","medal_type_id":1,"medal_type":"Type","achieve_time":"bad date"},{"achieve_id":"8","achieve_name":"Second","medal_type_id":"01"},{"achieve_id":true,"achieve_name":"Bad"},{"achieve_id":9},null]}""")!!
        assertEquals("Self",result.character!!.characterName);assertEquals(5,result.sourceRows);assertEquals(3,result.rejectedRows)
        assertEquals(listOf("7","8"),result.medals.map{it.achievementId});assertEquals(listOf("1","01"),result.medals.map{it.typeId});assertEquals("bad date",result.medals.first().achievedAt)
    }
    @Test fun nestedCharactersCannotBeUsedAsWallOwner() {
        assertNull(wall("""{"characterDetail":{"character_name":"Other","group_name":"Other"},"achieveInfo":[]}""")!!.character)
    }
}
