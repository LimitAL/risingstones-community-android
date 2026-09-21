package top.cxmeow.risingstones.feature.personaldata.ui.compose

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlineDayStamp

class PersonalDataFrontlineDatesTest {
    private val utc = ZoneId.of("UTC")
    private val hawaii = ZoneId.of("Pacific/Honolulu")
    @Test fun localTimeDoesNotAcquireAnImplicitSourceZone() {
        val value = FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 1, 1, 8, 30, 15))
        assertEquals("Jan 1, 2026, 8:30:15 AM", frontlineDateText(value, hawaii, Locale.US)?.replace('\u202f', ' '))
        assertEquals(frontlineDateText(value, utc, Locale.US), frontlineDateText(value, hawaii, Locale.US))
    }
    @Test fun calendarDateDoesNotBecomeAFalseMidnightOrChangeItsDay() {
        val value = FrontlineDayStamp.CalendarDate(LocalDate.of(2026, 1, 1))
        assertEquals("Jan 1, 2026", frontlineDateText(value, hawaii, Locale.US)?.replace('\u202f', ' '))
        assertEquals(frontlineDateText(value, utc, Locale.US), frontlineDateText(value, hawaii, Locale.US))
    }
    @Test fun actualOffsetTimeConvertsIntoTheExplicitDisplayZone() {
        val value = FrontlineDayStamp.OffsetTime(Instant.parse("2026-01-01T08:30:15Z"))
        assertEquals("Dec 31, 2025, 10:30:15 PM", frontlineDateText(value, hawaii, Locale.US)?.replace('\u202f', ' '))
        assertEquals("Jan 1, 2026, 8:30:15 AM", frontlineDateText(value, utc, Locale.US)?.replace('\u202f', ' '))
    }
    @Test fun unknownAndUnrepresentableDatesStayUnknown() {
        assertNull(frontlineDateText(null, utc, Locale.US))
        assertNull(frontlineDateText(FrontlineDayStamp.OffsetTime(Instant.MAX), utc, Locale.US))
    }
}
