package top.cxmeow.risingstones.feature.personaldata.data

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem

/** Exact fields consumed by the official Ultimate page, without changing the legacy reader. */
internal class PersonalDataUltimateApiReader(private val reader: PersonalDataRequestReader) {
    suspend fun records(): List<PersonalDataUltimateRecord> = rows("gaoNanFirst1", allowNullPlaceholders = true).map { row ->
        currentCoroutineContext().ensureActive()
        PersonalDataUltimateRecord(
            territoryType = row["territory_type"].positiveId() ?: throw PersonalDataException.MissingPayload,
            clearCount = row["clear_times"].count(), entriesBeforeFirstClear = row["enter_before_clear"].count(),
            firstClearJob = row["job_name"].text(), firstClearAt = row["log_time"].recordTime(),
            firstClearDurationSeconds = row["elapsed_time"].count(), deathsBeforeFirstClear = row["dead_times"].count(),
        )
    }

    suspend fun section(territoryType: Int, section: PersonalDataUltimateSection): PersonalDataUltimateData {
        require(territoryType > 0) { "Ultimate territory type must be positive" }
        val query = listOf(RisingStonesApiQueryItem("territory_type", territoryType.toString()))
        return when (section) {
            PersonalDataUltimateSection.Party -> PersonalDataUltimateData.Party(
                mappedRows("gaoNanTeam2", query) { row -> UltimatePartyMember(row.requiredText("character_namee"),
                    row["area_name"].text(), row["group_name"].text(), row["job_name"].text()) })
            PersonalDataUltimateSection.Jobs -> PersonalDataUltimateData.Jobs(
                mappedRows("gaoNanJob3", query) { row -> UltimateJobUsage(row.requiredText("job_name"), row["job_times"].count()) })
            PersonalDataUltimateSection.Partners -> PersonalDataUltimateData.Partners(
                mappedRows("gaoNanFriend4", query) { row -> UltimateCompanion(row.requiredText("team_chara_name"),
                    row["area_name"].text(), row["group_name"].text(), row["friend_times"].count()) })
            PersonalDataUltimateSection.Phases -> PersonalDataUltimateData.Phases(
                mappedRows("gaoNanPhase6", query) { row -> UltimatePhaseRecord(row.requiredText("phase"), row["log_time"].recordTime()) })
            PersonalDataUltimateSection.Deaths -> PersonalDataUltimateData.Deaths(
                mappedRows("gaoNanDeadPoint5", query) { row -> UltimateDeathRecord(row["point_x"].coordinate(), row["point_y"].coordinate()) })
        }
    }

    private suspend fun <T> mappedRows(endpoint: String, query: List<RisingStonesApiQueryItem>, map: (JsonObject) -> T): List<T> =
        rows(endpoint, query).map { currentCoroutineContext().ensureActive(); map(it) }

    private suspend fun rows(
        endpoint: String,
        query: List<RisingStonesApiQueryItem> = emptyList(),
        allowNullPlaceholders: Boolean = false,
    ): List<JsonObject> {
        val data = reader.read("api/home/dataCenter/$endpoint", query)["data"] as? JsonArray
            ?: throw PersonalDataException.MissingPayload
        return buildList {
            for (element in data) {
                currentCoroutineContext().ensureActive()
                // The page explicitly filters null placeholders only for gaoNanFirst1.
                if (allowNullPlaceholders && element == JsonNull) continue
                add(element as? JsonObject ?: throw PersonalDataException.MissingPayload)
            }
        }
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }
    ?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.requiredText(key: String) = this[key].text() ?: throw PersonalDataException.MissingPayload
private fun JsonElement?.numberText() = (this as? JsonPrimitive)?.contentOrNull?.trim()
private fun JsonElement?.count(): Long? = try {
    numberText()?.toBigDecimalOrNull()?.longValueExact()?.takeIf { it >= 0 }
} catch (_: ArithmeticException) { null }
private fun JsonElement?.positiveId() = numberText()
    ?.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it > 0 }
private fun JsonElement?.coordinate() = numberText()?.toBigDecimalOrNull()?.toDouble()?.takeIf(Double::isFinite)

private val ultimateSpacedLocalTime = DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME).toFormatter().withResolverStyle(ResolverStyle.STRICT)

private fun JsonElement?.recordTime(): UltimateRecordTime? {
    val value = text()?.trim() ?: return null
    val parsers = listOf<() -> UltimateRecordTime>(
        { UltimateRecordTime.CalendarDate(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)) },
        { UltimateRecordTime.LocalTime(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)) },
        { UltimateRecordTime.LocalTime(LocalDateTime.parse(value, ultimateSpacedLocalTime)) },
        { UltimateRecordTime.OffsetTime(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()) },
        { UltimateRecordTime.OffsetTime(Instant.parse(value)) },
    )
    for (parse in parsers) {
        try { return parse() } catch (_: DateTimeParseException) { }
    }
    return null
}
