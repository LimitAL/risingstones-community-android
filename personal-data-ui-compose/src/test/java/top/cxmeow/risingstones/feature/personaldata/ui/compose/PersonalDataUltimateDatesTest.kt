package top.cxmeow.risingstones.feature.personaldata.ui.compose

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateRecordTime

class PersonalDataUltimateDatesTest {
    private val utc = ZoneId.of("UTC")
    private val hawaii = ZoneId.of("Pacific/Honolulu")

    @Test fun calendarDatePreservesDatePrecisionAndDoesNotShiftItsDay() {
        val value = UltimateRecordTime.CalendarDate(LocalDate.of(2026, 1, 1))
        assertEquals("Jan 1, 2026", ultimateDateText(value, hawaii, Locale.US))
        assertEquals(ultimateDateText(value, utc, Locale.US), ultimateDateText(value, hawaii, Locale.US))
    }

    @Test fun localTimeKeepsItsClockTimeAndSecondsWithoutAssumingASourceZone() {
        val value = UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 1, 1, 8, 30, 15))
        assertEquals("Jan 1, 2026, 8:30:15 AM", ultimateDateText(value, hawaii, Locale.US)?.replace('\u202f', ' '))
        assertEquals(ultimateDateText(value, utc, Locale.US), ultimateDateText(value, hawaii, Locale.US))
    }

    @Test fun offsetTimeUsesTheExplicitDisplayZoneAndKeepsSecondsAcrossADateBoundary() {
        val value = UltimateRecordTime.OffsetTime(Instant.parse("2026-01-01T08:30:15Z"))
        assertEquals("Dec 31, 2025, 10:30:15 PM", ultimateDateText(value, hawaii, Locale.US)?.replace('\u202f', ' '))
        assertEquals("Jan 1, 2026, 8:30:15 AM", ultimateDateText(value, utc, Locale.US)?.replace('\u202f', ' '))
    }

    @Test fun missingOrUnrepresentableTimeRemainsUnknown() {
        assertNull(ultimateDateText(null, utc, Locale.US))
        assertNull(ultimateDateText(UltimateRecordTime.OffsetTime(Instant.MAX), utc, Locale.US))
    }
}
