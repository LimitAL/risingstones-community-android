package top.cxmeow.risingstones.feature.personaldata.ui.compose

import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponRecordTime as Time

class PersonalDataPhantomDatesTest {
    @Test fun calendarAndLocalDatesDoNotShiftWithDisplayZone() {
        val day = Time.CalendarDate(LocalDate.of(2026, 9, 20))
        val local = Time.LocalTime(LocalDateTime.of(2026, 9, 20, 1, 30))
        listOf(day, local).forEach { value ->
            assertEquals(phantomDateText(value, ZoneOffset.UTC, Locale.US), phantomDateText(value, ZoneId.of("America/Los_Angeles"), Locale.US))
        }
    }
    @Test fun explicitOffsetsConvertToDisplayZone() {
        val value = Time.OffsetTime(Instant.parse("2026-09-20T01:30:00Z"))
        assertNotEquals(phantomDateText(value, ZoneOffset.UTC, Locale.US), phantomDateText(value, ZoneId.of("America/Los_Angeles"), Locale.US))
    }
    @Test fun absentAndOutOfRangeTimestampStayUnknown() {
        assertNull(phantomDateText(null, ZoneOffset.UTC, Locale.US))
        assertNull(phantomDateText(Time.OffsetTime(Instant.MAX), ZoneOffset.UTC, Locale.US))
    }
}
