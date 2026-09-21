package top.cxmeow.risingstones.feature.personaldata.data

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.cxmeow.risingstones.feature.personaldata.domain.*

/** The official Frontline datasets are complete arrays; filtering belongs to the reader's caller. */
internal class PersonalDataFrontlineApiReader(private val reader: PersonalDataRequestReader) {
    suspend fun fetch(section: PersonalDataFrontlineSection): PersonalDataFrontlineData = when (section) {
        PersonalDataFrontlineSection.Overview -> PersonalDataFrontlineData.Overview(
            rows("frontline1TotalNew") { row -> FrontlineOverviewRecord(
                period = row.requiredText("data_time").period(),
                battles = row["fight_times"].count(), wins = row["win_times"].count(),
                kills = row["kill_times"].count(), winRate = row["win_rate"].fraction(),
                kda = row["kda"].quantity(), companyName = row["gc_id"].text(),
                pvpRank = row["pvp_rank"].count(), seriesLevel = row["series_level"].count(),
                elapsedHours = row["clear_time"].quantity(), occupiedObjectives = row["occupy_count"].count(),
                averages = FrontlineAverages(row["avg_kill"].quantity(), row["avg_assist"].quantity(), row["avg_dead"].quantity()),
                ranks = FrontlineRanks(row["kill_rank"].score(), row["heal_rank"].score(),
                    row["damaged_rank"].score(), row["damage_rank"].score(), row["dead_rank"].score(), row["assist_rank"].score()),
            ) })
        PersonalDataFrontlineSection.Weekly -> PersonalDataFrontlineData.Weekly(
            rows("frontline2WeekNew") { row -> FrontlineDayRecord(
                day = row.requiredText("part_date").dayStamp(), battles = row["fight_times"].count(),
                wins = row["win_times"].count(), kills = row["kill_times"].count(),
                deaths = row["dead_times"].count(), assists = row["assist_times"].count(),
            ) })
        PersonalDataFrontlineSection.Jobs -> PersonalDataFrontlineData.Jobs(
            rows("frontline3JobNew") { row -> FrontlineJobRecord(
                period = row.requiredText("data_time").period(), jobName = row.requiredText("job_name"),
                battles = row["times"].count(), useRate = row["use_rate"].fraction(), kills = row["kill_times"].count(),
                winRate = row["win_rate"].fraction(), kda = row["kda"].quantity(), kdaPercentile = row["kda_rate"].fraction(),
                limitBreaks = row["lb_times"].count(),
                averages = FrontlineAverages(row["avg_kill"].quantity(), row["avg_assist"].quantity(), row["avg_dead"].quantity(),
                    row["avg_damage"].quantity(), row["avg_heal"].quantity(), row["avg_damaged"].quantity()),
            ) })
        PersonalDataFrontlineSection.Best -> PersonalDataFrontlineData.Best(rows("frontline4Best", ::best))
        PersonalDataFrontlineSection.Maps -> PersonalDataFrontlineData.Maps(
            rows("frontline5Map") { row -> FrontlineMapRecord(row.requiredText("territory_type"),
                row["fight_times"].count(), row["win_times"].count(), row["kill_times"].count(), row["win_rate"].fraction()) })
        PersonalDataFrontlineSection.MapJobs -> PersonalDataFrontlineData.MapJobs(
            rows("frontline6MapJob") { row -> FrontlineMapJobRecord(row.requiredText("territory_type"), row.requiredText("job_name"),
                row["job_num"].count(), row["job_win_times"].count(), row["job_kill_times"].count(), row["job_win_rate"].fraction()) })
        PersonalDataFrontlineSection.Achievements -> PersonalDataFrontlineData.Achievements(
            rows("frontlineActiveDetail") { row -> FrontlineAchievementRecord(
                row["achieve_id"].positiveId() ?: throw PersonalDataException.MissingPayload,
                row["achieve_name"].text(), row["achieve_detail"].text(), row["log_time"].date(),
            ) })
    }

    private fun best(row: JsonObject) = FrontlineBestRecord(
        kind = when (row.requiredText("best_type")) {
            "kill" -> FrontlineBestKind.Kills
            "assist" -> FrontlineBestKind.Assists
            "damage" -> FrontlineBestKind.Damage
            "damaged" -> FrontlineBestKind.DamageTaken
            "heal" -> FrontlineBestKind.Healing
            else -> FrontlineBestKind.Unknown
        },
        mapName = row["territory_type"].text(), recordedAt = row["log_time"].date(), jobName = row["career"].text(),
        placement = when (row["result_rank"].count()) {
            0L -> FrontlinePlacement.First
            1L -> FrontlinePlacement.Second
            2L -> FrontlinePlacement.Third
            else -> null
        },
        kills = row["kill_times"].count(), deaths = row["dead_times"].count(), assists = row["assist"].count(),
        damage = row["total_damage"].count(), damageTaken = row["total_damaged"].count(), healing = row["total_heal"].count(),
        scores = listOf(3, 2, 1).map { FrontlineTeamScore(it, row["team${it}_score"].count()) },
    )

    private suspend fun <T> rows(endpoint: String, map: (JsonObject) -> T): List<T> {
        val data = reader.read("api/home/dataCenter/$endpoint", emptyList())["data"] as? JsonArray
            ?: throw PersonalDataException.MissingPayload
        return data.map {
            currentCoroutineContext().ensureActive()
            map(it as? JsonObject ?: throw PersonalDataException.MissingPayload)
        }
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }
    ?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.requiredText(key: String) = this[key].text() ?: throw PersonalDataException.MissingPayload
private fun String.period() = FrontlinePeriodKind.entries.firstOrNull { it.wireValue == this }
private fun JsonElement?.numberText() = (this as? JsonPrimitive)?.contentOrNull?.trim()
private fun JsonElement?.count(): Long? = try {
    numberText()?.toBigDecimalOrNull()?.longValueExact()?.takeIf { it >= 0 }
} catch (_: ArithmeticException) { null }
private fun JsonElement?.quantity(maximum: BigDecimal? = null) = numberText()?.toBigDecimalOrNull()
    ?.takeIf { it.signum() >= 0 && (maximum == null || it <= maximum) }
    ?.toDouble()?.takeIf(Double::isFinite)
private fun JsonElement?.fraction() = quantity(BigDecimal.ONE)
private fun JsonElement?.score() = quantity(BigDecimal.valueOf(100))
private fun JsonElement?.positiveId() = numberText()
    ?.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it > 0 }
private fun JsonElement?.date() = text()?.dayStamp()

private val spacedLocalTime = DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME).toFormatter().withResolverStyle(ResolverStyle.STRICT)

/** All Frontline dates preserve whether the source supplied a calendar day, local time or offset. */
private fun String.dayStamp(): FrontlineDayStamp? {
    val value = trim()
    val parsers = listOf<() -> FrontlineDayStamp>(
        { FrontlineDayStamp.CalendarDate(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)) },
        { FrontlineDayStamp.LocalTime(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)) },
        { FrontlineDayStamp.LocalTime(LocalDateTime.parse(value, spacedLocalTime)) },
        { FrontlineDayStamp.OffsetTime(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()) },
        { FrontlineDayStamp.OffsetTime(Instant.parse(value)) },
    )
    for (parse in parsers) {
        try { return parse() } catch (_: DateTimeParseException) { }
    }
    return null
}
