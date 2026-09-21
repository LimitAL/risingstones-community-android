package top.cxmeow.risingstones.feature.guild.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GuildIdTest {
    @Test
    fun preservesPositiveDecimalIdsWithoutAnIntegerRangeAssumption() {
        val value = "999999999999999999999999999999999999"
        assertEquals(value, GuildId(value).value)
    }

    @Test
    fun rejectsZeroSignsWhitespaceAndNonAsciiDigits() {
        listOf("", "0", "000", "-1", "+1", " 1", "1 ", "１", "١").forEach { value ->
            try {
                GuildId(value)
                fail("Expected invalid guild id: $value")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
