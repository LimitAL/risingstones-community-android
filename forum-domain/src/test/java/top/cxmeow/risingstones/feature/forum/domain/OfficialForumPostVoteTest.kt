package top.cxmeow.risingstones.feature.forum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class OfficialForumPostVoteTest {
    @Test
    fun selectionModeComesFromOptionTypeForBothTextAndPictureVotes() {
        for (displayType in listOf(1, 2)) {
            for (selectionType in listOf(1, 2)) {
                for (maximum in listOf(null, 1, 5)) {
                    val vote = OfficialForumPostVote(
                        id = "fixture", title = "Fixture", type = displayType,
                        minimumSelectionCount = 1, maximumSelectionCount = maximum,
                        levelRequirement = 0, endDateText = null, totalUserCount = 0,
                        options = listOf(OfficialForumPostVoteOption("1", 1, "Fixture option", null, selectionType, 0, false)),
                    )
                    assertEquals(selectionType == 2, vote.allowsMultipleSelection)
                }
            }
        }
    }

    @Test
    fun missingOptionsCannotBeMistakenForMultipleSelectionBecauseOfPictureType() {
        val vote = OfficialForumPostVote("fixture", "Fixture", 2, 1, 5, 0, null, 0, emptyList())
        assertFalse(vote.allowsMultipleSelection)
    }

    @Test
    fun deadlineAcceptsTheOfficialSecondsAndMillisecondsRepresentations() {
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), vote("1700000000").deadline())
        assertEquals(Instant.ofEpochMilli(1_700_000_000_123), vote("1700000000123").deadline())
        assertEquals(Instant.ofEpochMilli(1_700_000_000_125), vote("1700000000.125").deadline())
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), vote(" 1.7e9 ").deadline())
    }

    @Test
    fun deadlineUsesTenCharacterIntegerRuleWithoutGuessingCalendarDates() {
        assertEquals(Instant.ofEpochMilli(999_999_999), vote("999999999").deadline())
        assertEquals(Instant.ofEpochMilli(10_000_000_000), vote("10000000000").deadline())
        assertEquals(Instant.ofEpochMilli(-123_456_789_000), vote("-123456789").deadline())
        assertEquals(Instant.EPOCH, vote("0").deadline())
        assertEquals(Instant.ofEpochMilli(16), vote("0x10").deadline())
        assertEquals(Instant.ofEpochMilli(8), vote("0o10").deadline())
        assertEquals(Instant.ofEpochMilli(2), vote("0b10").deadline())
    }

    @Test
    fun absentInvalidAndNonFiniteDeadlinesDoNotBecomeAValidDate() {
        for (text in listOf(null, "", "  ", "\uFEFF", "2026-09-18", "2026-09-18T12:00:00Z",
            "NaN", "Infinity", "-Infinity", "1e999", "8640000000000001", "1700000000f",
            "0x1p2", "0x", "-0x10", "1_700_000_000", "1700000000 seconds")) {
            assertNull("Unexpected valid deadline for $text", vote(text).deadline())
        }
    }

    private fun vote(endDate: String?) =
        OfficialForumPostVote("fixture", "Fixture", 1, null, null, 0, endDate, 0, emptyList())
}
