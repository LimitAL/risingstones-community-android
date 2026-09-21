package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

class PersonalDataUltimateViewsTest {
    @Test fun summariesUseFirstDuplicateAndTotalRetainsUnknownAndOverflowInsteadOfInventingZero() {
        val first = ultimateRecord(733, 2)
        val second = ultimateRecord(733, 99)
        val rows = listOf(first, second, ultimateRecord(968, 3))
        assertEquals(listOf(first, rows.last()), PersonalDataUltimateViews.records(rows))
        assertEquals(5L, PersonalDataUltimateViews.totalClears(rows))
        assertEquals(3, rows.size)
        assertEquals(0L, PersonalDataUltimateViews.totalClears(emptyList()))
        assertNull(PersonalDataUltimateViews.totalClears(listOf(ultimateRecord(733, null))))
        assertNull(PersonalDataUltimateViews.totalClears(listOf(ultimateRecord(733, -1))))
        assertNull(PersonalDataUltimateViews.totalClears(listOf(ultimateRecord(733, Long.MAX_VALUE), ultimateRecord(968, 1))))
    }

    @Test fun partyUsesRoleGroupOrderStableWithinGroupsAndUnknownJobsAreLast() {
        fun member(name: String, job: String?) = UltimatePartyMember(name, null, null, job)
        val rows = listOf(member("Unknown1", "future"), member("Damage1", "D"), member("Tank2", "T2"),
            member("Heal", "H"), member("Tank1", "T1"), member("Damage2", "D"), member("Unknown2", null))
        val order = mapOf("T1" to 0, "T2" to 0, "H" to 1, "D" to 2)
        assertEquals(listOf("Tank2", "Tank1", "Heal", "Damage1", "Damage2", "Unknown1", "Unknown2"),
            PersonalDataUltimateViews.party(rows) { order[it] }.map { it.characterName })
        assertEquals("Unknown1", rows.first().characterName)
    }

    @Test fun jobsAndPartnersKeepUnknownCountsLastAndPreserveTiesAndLocation() {
        val jobs = listOf(UltimateJobUsage("Unknown", null), UltimateJobUsage("Low", 1), UltimateJobUsage("High", 20), UltimateJobUsage("Tie", 20))
        assertEquals(listOf("High", "Tie", "Low", "Unknown"), PersonalDataUltimateViews.jobs(jobs).map { it.jobName })
        val partners = listOf(UltimateCompanion("Unknown", "Area", "World", null), UltimateCompanion("First", "Area", "World", 20),
            UltimateCompanion("Tie", "Area2", "World2", 20))
        val result = PersonalDataUltimateViews.partners(partners)
        assertEquals(listOf("First", "Tie", "Unknown"), result.map { it.characterName })
        assertEquals("Area2", result[1].areaName)
        assertEquals("World2", result[1].groupName)
    }

    @Test fun phaseComparisonUsesExplicitZoneWithoutChangingFinishOrUnknownStages() {
        val local = UltimatePhaseRecord("p1", UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 12, 0)))
        val offset = UltimatePhaseRecord("finish", UltimateRecordTime.OffsetTime(Instant.parse("2026-09-20T08:00:00Z")))
        val unknown = UltimatePhaseRecord("future-stage", null)
        val rows = listOf(unknown, offset, local)
        assertEquals(listOf(local, offset, unknown), PersonalDataUltimateViews.phases(rows, ZoneId.of("Asia/Shanghai")))
        assertEquals(listOf(offset, local, unknown), PersonalDataUltimateViews.phases(rows, ZoneId.of("UTC")))
        assertEquals(listOf(unknown, offset, local), rows)
    }

    @Test fun calendarPhaseUsesLocalDayStartAndEqualTimestampsKeepSourceOrder() {
        val calendar = UltimatePhaseRecord("p1", UltimateRecordTime.CalendarDate(LocalDate.of(2026, 9, 20)))
        val offset = UltimatePhaseRecord("p2", UltimateRecordTime.OffsetTime(Instant.parse("2026-09-19T16:00:00Z")))
        assertEquals(listOf(calendar, offset), PersonalDataUltimateViews.phases(listOf(calendar, offset), ZoneId.of("Asia/Shanghai")))
        assertEquals(listOf(offset, calendar), PersonalDataUltimateViews.phases(listOf(calendar, offset), ZoneId.of("UTC")))
        assertTrue(calendar.reachedAt is UltimateRecordTime.CalendarDate)
    }

    @Test fun deathTransformsKeepRawRecordAndUseSpecialOriginOnlyFor733() {
        val record = UltimateDeathRecord(103.0, 97.0)
        val baha = PersonalDataUltimateViews.deathPlot(listOf(record), 733).points.single()
        val other = PersonalDataUltimateViews.deathPlot(listOf(record), 968).points.single()
        assertEquals(103.0, baha.x, 0.0); assertEquals(-97.0, baha.y, 0.0)
        assertEquals(3.0, other.x, 0.0); assertEquals(3.0, other.y, 0.0)
        assertSame(record, other.record)
        assertEquals(0, other.sourceIndex)
        assertEquals(UltimateDeathRecord(103.0, 97.0), record)
    }

    @Test fun invalidCoordinatesAreExcludedAndValidPointIndicesCorrespondToSourceRows() {
        val rows = listOf(UltimateDeathRecord(null, 1.0), UltimateDeathRecord(1.0, Double.NaN),
            UltimateDeathRecord(Double.POSITIVE_INFINITY, 1.0), UltimateDeathRecord(-1.0, -2.0), UltimateDeathRecord(3.0, 4.0))
        val plot = PersonalDataUltimateViews.deathPlot(rows, 733)
        assertEquals(3, plot.excludedCount)
        assertEquals(listOf(3, 4), plot.points.map { it.sourceIndex })
        assertEquals(listOf(rows[3], rows[4]), plot.points.map { it.record })
        assertEquals(5, rows.size)
    }

    @Test fun symmetricAxesFollowNiceIntervalsAndNeverCropEitherDimension() {
        listOf(1.0 to 1.0, 1.1 to 2.5, 2.1 to 2.5, 5.1 to 10.0, 25.0 to 25.0, 51.0 to 100.0).forEach { (extent, expected) ->
            val plot = PersonalDataUltimateViews.deathPlot(listOf(UltimateDeathRecord(-extent, extent / 2)), 733)
            assertEquals(expected, plot.axis.max, 0.000001)
            assertEquals(-expected, plot.axis.min, 0.000001)
            assertEquals(expected / 5, plot.axis.interval, 0.000001)
            assertEquals(10, plot.axis.splitNumber)
            assertTrue(plot.points.all { it.x in plot.axis.min..plot.axis.max && it.y in plot.axis.min..plot.axis.max })
        }
    }

    @Test fun emptyAndZeroCloudsHaveFiniteNonzeroAxes() {
        listOf(emptyList(), listOf(UltimateDeathRecord(100.0, 100.0)), listOf(UltimateDeathRecord(null, null))).forEach { rows ->
            val axis = PersonalDataUltimateViews.deathPlot(rows, 968).axis
            assertEquals(UltimatePlotAxis(-1.0, 1.0, 0.2, 10), axis)
        }
    }

    @Test fun extremelyLargeAndTinyFiniteCoordinatesStillProduceUsableAxisAndDoNotBecomeUnknown() {
        for (value in listOf(Double.MAX_VALUE, Double.MIN_VALUE)) {
            val plot = PersonalDataUltimateViews.deathPlot(listOf(UltimateDeathRecord(value, 0.0)), 733)
            assertEquals(1, plot.points.size)
            assertEquals(0, plot.excludedCount)
            assertTrue(plot.axis.min.isFinite()); assertTrue(plot.axis.max.isFinite())
            assertTrue(plot.axis.interval.isFinite() && plot.axis.interval > 0)
            assertTrue(plot.axis.max >= value)
        }
    }

    @Test fun projectionDoesNotInventA500RowClientLimit() {
        val rows = (1..501).map { UltimateDeathRecord(it.toDouble(), it.toDouble()) }
        val plot = PersonalDataUltimateViews.deathPlot(rows, 733)
        assertEquals(501, plot.points.size)
        assertEquals(rows, plot.points.map { it.record })
    }
}

internal fun ultimateRecord(id: Int = 968, clears: Long? = 1) = PersonalDataUltimateRecord(id, clears, 3, "Job", null, 90, 8)
