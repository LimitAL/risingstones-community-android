package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

class PersonalDataFrontlineViewsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun sevenCalendarDaysCrossYearAndLeapDayWithoutIncludingToday() {
        for (today in listOf(LocalDate.of(2026, 1, 3), LocalDate.of(2024, 3, 2))) {
            val rows = listOf(frontlineDay(today, 100), frontlineDay(today.minusDays(8), 200), frontlineDay(today.minusDays(1), 3))
            val view = PersonalDataFrontlineViews.weekly(rows, today, zone)
            assertEquals((7L downTo 1L).map(today::minusDays), view.days.map { it.date })
            assertEquals(3L, view.totals.battles)
            assertEquals(6, view.days.count { it.isMissing })
        }
        assertTrue(PersonalDataFrontlineViews.weekly(emptyList(), LocalDate.of(2024, 3, 2), zone).days.any { it.date == LocalDate.of(2024, 2, 29) })
    }

    @Test fun calendarLocalAndOffsetDatesUseTheirOwnSemanticsAcrossZoneAndDstBoundaries() {
        val calendar = FrontlineDayStamp.CalendarDate(LocalDate.of(2026, 1, 1))
        val local = FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 1, 1, 23, 59))
        val offset = FrontlineDayStamp.OffsetTime(Instant.parse("2025-12-31T20:00:00Z"))
        assertEquals(LocalDate.of(2026, 1, 1), PersonalDataFrontlineViews.date(calendar, ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDate.of(2026, 1, 1), PersonalDataFrontlineViews.date(local, ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDate.of(2026, 1, 1), PersonalDataFrontlineViews.date(offset, zone))
        assertEquals(LocalDate.of(2025, 12, 31), PersonalDataFrontlineViews.date(offset, ZoneId.of("America/Los_Angeles")))
        val dst = PersonalDataFrontlineViews.weekly(emptyList(), LocalDate.of(2026, 3, 10), ZoneId.of("America/New_York"))
        assertEquals(7, dst.days.map { it.date }.distinct().size)
        assertNull(PersonalDataFrontlineViews.date(FrontlineDayStamp.OffsetTime(Instant.MAX), zone))
    }

    @Test fun duplicateDatesTakeFirstSourceRowAndUnknownDateRecordsRemainVisibleSeparately() {
        val today = LocalDate.of(2026, 9, 20)
        val first = frontlineDay(today.minusDays(1), 3)
        val second = first.copy(battles = 99)
        val unknown = first.copy(day = null, battles = 5)
        val rows = listOf(first, second, unknown)
        val week = PersonalDataFrontlineViews.weekly(rows, today, zone)
        assertEquals(first, week.days.last().record)
        assertEquals(3L, week.totals.battles)
        assertEquals(listOf(unknown), week.unknownDateRows)
        assertEquals(listOf(first, second, unknown), rows)
    }

    @Test fun missingDayBecomesZeroButExistingRowMissingWinsStaysUnknown() {
        val today = LocalDate.of(2026, 9, 20)
        val week = PersonalDataFrontlineViews.weekly(listOf(frontlineDay(today.minusDays(1), 5).copy(wins = null)), today, zone)
        assertEquals(0L, week.days.first().wins)
        assertEquals(0.0, week.days.first().winRate)
        assertNull(week.days.last().wins)
        assertNull(week.days.last().winRate)
        assertNull(week.totals.wins)
        assertNull(week.totals.winRate)
        assertEquals(5L, week.totals.battles)
    }

    @Test fun weeklyRatesUseSummedRawCountsInsteadOfMeanOfDailyRates() {
        val today = LocalDate.of(2026, 9, 20)
        val rows = listOf(frontlineDay(today.minusDays(1), 1).copy(wins = 1, kills = 10, assists = 0, deaths = 1),
            frontlineDay(today.minusDays(2), 9).copy(wins = 0, kills = 0, assists = 10, deaths = 9))
        val week = PersonalDataFrontlineViews.weekly(rows, today, zone)
        assertEquals(0.1, week.totals.winRate!!, 0.00001)
        assertEquals(2.0, week.totals.kda!!, 0.00001)
        assertEquals(10L, week.totals.battles)
    }

    @Test fun zeroDeathsUseOneWhileInvalidAndContradictoryCountsCannotMakePlausibleRatios() {
        val today = LocalDate.of(2026, 9, 20)
        val zero = frontlineDay(today.minusDays(1), 0).copy(kills = 7, assists = 3, deaths = 0)
        val week = PersonalDataFrontlineViews.weekly(listOf(zero), today, zone)
        assertEquals(10.0, week.totals.kda)
        assertEquals(0.0, week.totals.winRate)
        val invalid = zero.copy(battles = 1, wins = 2, deaths = -1)
        val bad = PersonalDataFrontlineViews.weekly(listOf(invalid, frontlineDay(today.minusDays(2), 10)), today, zone)
        assertNull(bad.days.last().winRate)
        assertNull(bad.totals.winRate)
        assertNull(bad.totals.deaths)
        assertNull(bad.totals.kda)
    }

    @Test fun countAndKdaAdditionOverflowProduceUnknownWithoutLosingIndependentTotals() {
        val today = LocalDate.of(2026, 9, 20)
        val rows = listOf(frontlineDay(today.minusDays(1), Long.MAX_VALUE).copy(kills = Long.MAX_VALUE, assists = 1), frontlineDay(today.minusDays(2), 1))
        val week = PersonalDataFrontlineViews.weekly(rows, today, zone)
        assertNull(week.totals.battles)
        assertNull(week.totals.winRate)
        assertNull(week.totals.kda)
        assertNull(week.days.last().kda)
        assertEquals(0L, week.totals.wins)
    }

    @Test fun metricsExposeFractionAndUnknownRatherThanFormattedOrInventedValues() {
        val today = LocalDate.of(2026, 9, 20)
        val row = frontlineDay(today.minusDays(1), 4).copy(wins = 1, kills = null)
        val day = PersonalDataFrontlineViews.weekly(listOf(row), today, zone).days.last()
        assertEquals(0.25, day.value(PersonalDataFrontlineWeeklyMetric.WinRate))
        assertEquals(4.0, day.value(PersonalDataFrontlineWeeklyMetric.Battles))
        assertNull(day.value(PersonalDataFrontlineWeeklyMetric.Kills))
        assertNull(day.value(PersonalDataFrontlineWeeklyMetric.Kda))
    }

    @Test fun overviewAndBestUseFirstMatchingIdentityAndUnknownPeriodNeverMeansTotal() {
        val total = frontlineOverview(FrontlinePeriodKind.Total, 3)
        val unknown = frontlineOverview(null, 99)
        assertEquals(total, PersonalDataFrontlineViews.overview(listOf(unknown, total, total.copy(battles = 8)), FrontlinePeriodKind.Total))
        assertNull(PersonalDataFrontlineViews.overview(listOf(unknown), FrontlinePeriodKind.Total))
        val best = frontlineBest().copy(kills = 1)
        assertEquals(best, PersonalDataFrontlineViews.best(listOf(best, best.copy(kills = 99)), FrontlineBestKind.Kills))
        assertNull(PersonalDataFrontlineViews.best(listOf(best), FrontlineBestKind.Healing))
    }

    @Test fun jobsChooseWithinPeriodSortByUsageAndKeepOfficialFirstAfterBattleSort() {
        val rows = listOf(frontlineJob("Elsewhere", FrontlinePeriodKind.Since51, 999, 1.0),
            frontlineJob("A", battles = 3, usage = 0.5), frontlineJob("A", battles = 10, usage = 0.1),
            frontlineJob("B", battles = 1, usage = 0.8), frontlineJob("Unknown", usage = Double.NaN))
        val jobs = PersonalDataFrontlineViews.jobs(rows, FrontlinePeriodKind.Total)
        assertEquals(listOf("B", "A", "Unknown"), jobs.map { it.jobName })
        assertEquals(10L, jobs[1].battles)
        assertEquals(5, rows.size)
    }

    @Test fun scoresUseTeam321AndDoNotDivideByMissingZeroNegativeOrOverflowedTotal() {
        val best = frontlineBest().copy(scores = listOf(FrontlineTeamScore(1, 30), FrontlineTeamScore(2, 20), FrontlineTeamScore(3, 50), FrontlineTeamScore(3, 999)))
        val scores = PersonalDataFrontlineViews.scores(best)
        assertEquals(listOf(3, 2, 1), scores.map { it.teamNumber })
        assertEquals(listOf(0.5, 0.2, 0.3), scores.map { it.fraction })
        listOf(listOf(0L, 0L, 0L), listOf(null, 2L, 3L), listOf(-1L, 2L, 3L), listOf(Long.MAX_VALUE, 1L, 1L)).forEach { values ->
            val invalid = best.copy(scores = values.mapIndexed { index, value -> FrontlineTeamScore(index + 1, value) })
            assertTrue(PersonalDataFrontlineViews.scores(invalid).all { it.fraction == null })
        }
    }

    @Test fun mapAllUsesFirstSummaryAndSingleJobUsesOnlyExactMapAndJob() {
        val maps = listOf(FrontlineMapRecord("A", 2, 1, 3, 0.5), FrontlineMapRecord("A", 99, 0, 0, 0.0))
        val jobs = listOf(FrontlineMapJobRecord("B", "Job", 80, 0, 0, 0.0), FrontlineMapJobRecord("A", "Job", 1, 1, 2, 1.0))
        assertEquals(maps.first(), PersonalDataFrontlineViews.mapStats(maps, emptyList(), "A", null))
        assertEquals(1L, PersonalDataFrontlineViews.mapStats(emptyList(), jobs, "A", "Job")?.battles)
        assertNull(PersonalDataFrontlineViews.mapStats(maps, jobs, "A", "Absent"))
        assertEquals(listOf("Official", "A", "B"), PersonalDataFrontlineViews.mapNames(PersonalDataFrontlineCatalogs(listOf("Official", "Official")), maps, jobs))
    }

    @Test fun achievementsKeepDuplicateUnknownRecordsAndOnlyAddProvenCatalogRows() {
        val records = listOf(FrontlineAchievementRecord(1, "First", null, FrontlineDayStamp.OffsetTime(Instant.ofEpochSecond(1))),
            FrontlineAchievementRecord(1, "Again", null, FrontlineDayStamp.OffsetTime(Instant.ofEpochSecond(2))), FrontlineAchievementRecord(99, "Unknown", null, null))
        val catalogs = PersonalDataFrontlineCatalogs(achievements = listOf(PersonalDataAchievementCatalogEntry(1, "Definition", null, null),
            PersonalDataAchievementCatalogEntry(2, "Unobtained", null, null), PersonalDataAchievementCatalogEntry(2, "Duplicate", null, null)))
        val rows = PersonalDataFrontlineViews.achievements(records, catalogs, true, "")
        assertEquals(listOf(1, 1, 99, 2), rows.map { it.achievementId })
        assertEquals("Again", rows.first().record?.name)
        assertNull(rows[2].catalog)
        assertEquals(3, PersonalDataFrontlineViews.achievements(records, null, true, "").size)
        assertEquals(1, PersonalDataFrontlineViews.achievements(records, catalogs, true, " unobtained ").size)
    }

    @Test fun achievementLocalAndOffsetTimesUseExplicitZoneWithoutChangingOriginalStamps() {
        val local = FrontlineAchievementRecord(1, "Local", null, FrontlineDayStamp.LocalTime(LocalDateTime.of(2026, 9, 20, 12, 0)))
        val offset = FrontlineAchievementRecord(2, "Offset", null, FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-20T08:00:00Z")))
        val calendar = FrontlineAchievementRecord(3, "Calendar", null, FrontlineDayStamp.CalendarDate(LocalDate.of(2026, 9, 20)))
        val unknown = FrontlineAchievementRecord(4, "Unknown", null, null)
        val records = listOf(calendar, local, unknown, offset)
        val catalogs = PersonalDataFrontlineCatalogs(achievements = listOf(PersonalDataAchievementCatalogEntry(5, "Unobtained", null, null)))
        val utc = PersonalDataFrontlineViews.achievements(records, catalogs, true, "", ZoneId.of("UTC"))
        val shanghai = PersonalDataFrontlineViews.achievements(records, catalogs, true, "", ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(1, 2, 3, 4, 5), utc.map { it.achievementId })
        assertEquals(listOf(2, 1, 3, 4, 5), shanghai.map { it.achievementId })
        assertSame(local, shanghai[1].record)
        assertSame(calendar, shanghai[2].record)
        assertEquals(listOf(calendar, local, unknown, offset), records)
    }

    @Test fun achievementCalendarDateSortsAtLocalDayStartWithStableEqualTimeOrder() {
        val offset = FrontlineAchievementRecord(1, "Offset", null, FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-19T16:00:00Z")))
        val calendar = FrontlineAchievementRecord(2, "Calendar", null, FrontlineDayStamp.CalendarDate(LocalDate.of(2026, 9, 20)))
        assertEquals(listOf(1, 2), PersonalDataFrontlineViews.achievements(listOf(offset, calendar), null, false, "", zone).map { it.achievementId })
        assertEquals(listOf(2, 1), PersonalDataFrontlineViews.achievements(listOf(offset, calendar), null, false, "", ZoneId.of("UTC")).map { it.achievementId })
    }
}

internal fun frontlineDay(day: LocalDate, battles: Long = 1) = FrontlineDayRecord(FrontlineDayStamp.CalendarDate(day), battles, 0, 0, 0, 0)
internal fun frontlineOverview(period: FrontlinePeriodKind? = FrontlinePeriodKind.Total, battles: Long = 1) = FrontlineOverviewRecord(
    period, battles, 0, 0, 0.0, 0.0, null, null, null, null, null, FrontlineAverages(), FrontlineRanks(null, null, null, null, null, null))
internal fun frontlineJob(name: String, period: FrontlinePeriodKind = FrontlinePeriodKind.Total, battles: Long = 1, usage: Double? = 0.5) =
    FrontlineJobRecord(period, name, battles, usage, 0, 0.0, 0.0, null, null, FrontlineAverages())
internal fun frontlineBest() = FrontlineBestRecord(FrontlineBestKind.Kills, null, null, null, null, null, null, null, null, null, null, emptyList())
