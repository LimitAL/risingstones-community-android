package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.LocalDate
import java.time.ZoneId
import java.time.DateTimeException
import java.time.Instant
import top.cxmeow.risingstones.feature.personaldata.domain.*

enum class PersonalDataFrontlineWeeklyMetric { Battles, Wins, Kills, Deaths, WinRate, Kda }

data class FrontlineDayView(
    val date: LocalDate,
    val record: FrontlineDayRecord?,
    val battles: Long?, val wins: Long?, val kills: Long?, val deaths: Long?, val assists: Long?,
    val winRate: Double?, val kda: Double?,
) {
    val isMissing: Boolean get() = record == null
    fun value(metric: PersonalDataFrontlineWeeklyMetric): Double? = when (metric) {
        PersonalDataFrontlineWeeklyMetric.Battles -> battles?.toDouble()
        PersonalDataFrontlineWeeklyMetric.Wins -> wins?.toDouble()
        PersonalDataFrontlineWeeklyMetric.Kills -> kills?.toDouble()
        PersonalDataFrontlineWeeklyMetric.Deaths -> deaths?.toDouble()
        PersonalDataFrontlineWeeklyMetric.WinRate -> winRate
        PersonalDataFrontlineWeeklyMetric.Kda -> kda
    }
}

data class FrontlineWeekTotals(
    val battles: Long?, val wins: Long?, val kills: Long?, val deaths: Long?, val assists: Long?,
    val winRate: Double?, val kda: Double?,
)
data class FrontlineWeekView(
    val days: List<FrontlineDayView>,
    val unknownDateRows: List<FrontlineDayRecord>,
    val totals: FrontlineWeekTotals,
)
data class FrontlineScoreView(val teamNumber: Int, val points: Long?, val fraction: Double?)
data class FrontlineAchievementRow(
    val achievementId: Int,
    val catalog: PersonalDataAchievementCatalogEntry?,
    val record: FrontlineAchievementRecord?,
)

/** Read-only local projections. Returned rates are fractions; formatting belongs to the caller. */
object PersonalDataFrontlineViews {
    fun date(stamp: FrontlineDayStamp?, zone: ZoneId): LocalDate? = try { when (stamp) {
        is FrontlineDayStamp.CalendarDate -> stamp.value
        is FrontlineDayStamp.LocalTime -> stamp.value.toLocalDate()
        is FrontlineDayStamp.OffsetTime -> stamp.value.atZone(zone).toLocalDate()
        null -> null
    } } catch (_: DateTimeException) { null }

    fun weekly(rows: List<FrontlineDayRecord>, today: LocalDate, zone: ZoneId): FrontlineWeekView {
        val byDate = rows.map { date(it.day, zone) to it }
        val days = (7L downTo 1L).map { ago ->
            val day = today.minusDays(ago)
            val record = byDate.firstOrNull { it.first == day }?.second
            fun count(read: (FrontlineDayRecord) -> Long?): Long? = if (record == null) 0 else read(record).nonnegative()
            val battles = count { it.battles }; val wins = count { it.wins }; val kills = count { it.kills }
            val deaths = count { it.deaths }; val assists = count { it.assists }
            FrontlineDayView(day, record, battles, wins, kills, deaths, assists, winRate(battles, wins), kda(kills, assists, deaths))
        }
        val battles = exactSum(days.map { it.battles }); val wins = exactSum(days.map { it.wins })
        val kills = exactSum(days.map { it.kills }); val deaths = exactSum(days.map { it.deaths }); val assists = exactSum(days.map { it.assists })
        // Unknown counts or contradictory daily results cannot become an apparently valid weekly ratio.
        val rate = if (days.all { it.winRate != null }) winRate(battles, wins) else null
        return FrontlineWeekView(days, byDate.filter { it.first == null }.map { it.second },
            FrontlineWeekTotals(battles, wins, kills, deaths, assists, rate, kda(kills, assists, deaths)))
    }

    fun overview(rows: List<FrontlineOverviewRecord>, period: FrontlinePeriodKind): FrontlineOverviewRecord? = rows.firstOrNull { it.period == period }

    fun jobs(rows: List<FrontlineJobRecord>, period: FrontlinePeriodKind): List<FrontlineJobRecord> = rows
        .filter { it.period == period && it.jobName.isNotBlank() }
        .sortedWith(compareByDescending<FrontlineJobRecord> { it.battles.nonnegative() })
        .distinctBy { it.jobName }
        .sortedWith(compareByDescending<FrontlineJobRecord> { it.useRate.validFraction() })

    fun best(rows: List<FrontlineBestRecord>, kind: FrontlineBestKind): FrontlineBestRecord? = rows.firstOrNull { it.kind == kind }

    fun scores(record: FrontlineBestRecord): List<FrontlineScoreView> {
        val scores = listOf(3, 2, 1).map { team -> team to record.scores.firstOrNull { it.teamNumber == team }?.points.nonnegative() }
        val total = exactSum(scores.map { it.second })?.takeIf { it > 0 }
        return scores.map { (team, points) -> FrontlineScoreView(team, points, if (total == null || points == null) null else points.toDouble() / total) }
    }

    fun mapNames(catalogs: PersonalDataFrontlineCatalogs?, maps: List<FrontlineMapRecord>, jobs: List<FrontlineMapJobRecord>): List<String> =
        (catalogs?.mapNames.orEmpty() + maps.map { it.mapName } + jobs.map { it.mapName }).filter(String::isNotBlank).distinct()

    fun mapJobs(rows: List<FrontlineMapJobRecord>, map: String?): List<FrontlineMapJobRecord> =
        if (map == null) emptyList() else rows.filter { it.mapName == map && it.jobName.isNotBlank() }.distinctBy { it.jobName }

    fun mapStats(maps: List<FrontlineMapRecord>, jobs: List<FrontlineMapJobRecord>, map: String?, job: String?): FrontlineMapRecord? {
        if (map == null) return null
        if (job == null) return maps.firstOrNull { it.mapName == map }
        return jobs.firstOrNull { it.mapName == map && it.jobName == job }?.let { FrontlineMapRecord(it.mapName, it.battles, it.wins, it.kills, it.winRate) }
    }

    fun achievements(
        records: List<FrontlineAchievementRecord>, catalogs: PersonalDataFrontlineCatalogs?,
        includeUnobtained: Boolean, query: String, zone: ZoneId = ZoneId.systemDefault(),
    ): List<FrontlineAchievementRow> {
        val definitions = catalogs?.achievements.orEmpty().distinctBy { it.achievementId }
        val byId = definitions.associateBy { it.achievementId }
        val obtained = records.map { it.achievementId }.toSet()
        val rows = records.map { FrontlineAchievementRow(it.achievementId, byId[it.achievementId], it) } +
            if (includeUnobtained) definitions.filter { it.achievementId !in obtained }.map { FrontlineAchievementRow(it.achievementId, it, null) } else emptyList()
        return rows.filter { row ->
            listOf(row.record?.name, row.record?.detail, row.catalog?.name, row.catalog?.detail, row.achievementId.toString())
                .filterNotNull().any { it.contains(query.trim(), ignoreCase = true) }
        }.sortedWith(compareByDescending<FrontlineAchievementRow> { it.record != null }
            .thenByDescending { sortInstant(it.record?.obtainedAt, zone) })
    }

    // A calendar date sorts at the start of that day in the caller's zone. This
    // comparison key does not change the original date/local-time display value.
    private fun sortInstant(stamp: FrontlineDayStamp?, zone: ZoneId): Instant? = try { when (stamp) {
        is FrontlineDayStamp.CalendarDate -> stamp.value.atStartOfDay(zone).toInstant()
        is FrontlineDayStamp.LocalTime -> stamp.value.atZone(zone).toInstant()
        is FrontlineDayStamp.OffsetTime -> stamp.value
        null -> null
    } } catch (_: DateTimeException) { null }

    private fun winRate(battles: Long?, wins: Long?): Double? = when {
        battles == null || wins == null || wins > battles -> null
        battles == 0L -> 0.0
        else -> wins.toDouble() / battles
    }
    private fun kda(kills: Long?, assists: Long?, deaths: Long?): Double? {
        val total = exactSum(listOf(kills, assists)) ?: return null
        return deaths?.let { total.toDouble() / if (it == 0L) 1 else it }
    }
    private fun exactSum(values: List<Long?>): Long? {
        var total = 0L
        for (value in values) {
            if (value == null || value < 0 || value > Long.MAX_VALUE - total) return null
            total += value
        }
        return total
    }
    private fun Long?.nonnegative(): Long? = this?.takeIf { it >= 0 }
    private fun Double?.validFraction(): Double? = this?.takeIf { it.isFinite() && it in 0.0..1.0 }
}
