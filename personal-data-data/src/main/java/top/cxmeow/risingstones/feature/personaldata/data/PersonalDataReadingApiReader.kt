package top.cxmeow.risingstones.feature.personaldata.data

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFishingRank
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFishingRankingKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataGlamourSetRecord
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataRaceUsage

/** The four detail routes consume full arrays. Filtering and display limits are local concerns. */
internal class PersonalDataReadingApiReader(private val reader: PersonalDataRequestReader) {
    suspend fun fishingRanking(kind: PersonalDataFishingRankingKind): List<PersonalDataFishingRank> {
        val endpoint = if (kind == PersonalDataFishingRankingKind.Fish) "fishNum2" else "fishBait3"
        val countField = if (kind == PersonalDataFishingRankingKind.Fish) "fish_num" else "bait_num"
        return rows(endpoint).map { row ->
            currentCoroutineContext().ensureActive()
            PersonalDataFishingRank(
                row.requiredName("catalog_name"),
                row[countField].nonNegativeLong() ?: throw PersonalDataException.MissingPayload,
                row["fish_type"].textOrNull(),
            )
        }
    }

    suspend fun raceUsage(): List<PersonalDataRaceUsage> = rows("getDressRace1").map { row ->
        currentCoroutineContext().ensureActive()
        personalDataRaceUsage(row)
    }

    suspend fun glamourSetRecords(): List<PersonalDataGlamourSetRecord> {
        val occurrences = mutableMapOf<String, Int>()
        return rows("getDressFullset5").map { row ->
            currentCoroutineContext().ensureActive()
            val setId = row["setitem"].positiveInt() ?: throw PersonalDataException.MissingPayload
            val parts = row["partitem"].textOrNull()?.split(',')
            val itemIds = parts.orEmpty().map { JsonPrimitive(it.trim()).positiveInt() }
            // Only the route's known record fields participate. Extra response fields are not identity.
            val canonical = JsonArray(listOf(
                JsonPrimitive(setId),
                (row["partitem"] ?: JsonNull).canonical(),
                (row["log_time"] ?: JsonNull).canonical(),
            )).toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).hex()
            val occurrence = occurrences.getOrDefault(digest, 0)
            occurrences[digest] = occurrence + 1
            PersonalDataGlamourSetRecord(
                key = "$digest-$occurrence",
                setId = setId,
                itemIds = itemIds.filterNotNull().toSet(),
                recordedAt = row["log_time"].textOrNull()?.let(::personalDataRecordedInstant),
                hasInvalidItemIds = parts == null || itemIds.any { it == null },
            )
        }
    }

    private suspend fun rows(endpoint: String): List<JsonObject> {
        val data = reader.read("api/home/dataCenter/$endpoint", emptyList())["data"] as? JsonArray
            ?: throw PersonalDataException.MissingPayload
        return data.map { it as? JsonObject ?: throw PersonalDataException.MissingPayload }
    }
}

internal fun personalDataRaceUsage(row: JsonObject): PersonalDataRaceUsage = PersonalDataRaceUsage(
    race = row.requiredName("race"),
    gender = row.requiredName("gender"),
    proportion = row["continue_rate"].numberText()?.toBigDecimalOrNull()?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..1.0 },
    days = row["continue_days"].nonNegativeLong(),
    isCurrent = row["now_rn"].nonNegativeLong() == 1L,
    isMostUsed = row["rate_rn"].nonNegativeLong() == 1L,
)

private fun JsonObject.requiredName(field: String): String =
    this[field].textOrNull() ?: throw PersonalDataException.MissingPayload

private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonElement?.numberText(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()

private fun JsonElement?.nonNegativeLong(): Long? = try {
    numberText()?.toBigDecimalOrNull()?.longValueExact()?.takeIf { it >= 0 }
} catch (_: ArithmeticException) { null }

private fun JsonElement?.positiveInt(): Int? = numberText()
    ?.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it > 0 }

private val officialLocalTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    .withResolverStyle(ResolverStyle.STRICT)
private val officialZone = ZoneId.of("Asia/Shanghai")

internal fun personalDataRecordedInstant(raw: String): Instant? {
    val value = raw.trim()
    val parsers = listOf<() -> Instant>(
        { Instant.parse(value) },
        { OffsetDateTime.parse(value).toInstant() },
        { LocalDateTime.parse(value).atZone(officialZone).toInstant() },
        { LocalDateTime.parse(value, officialLocalTime).atZone(officialZone).toInstant() },
        { LocalDate.parse(value).atStartOfDay(officialZone).toInstant() },
    )
    for (parse in parsers) {
        try { return parse() } catch (_: DateTimeParseException) { }
    }
    return null
}

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.canonical() })
    is JsonArray -> JsonArray(map { it.canonical() })
    else -> this
}

private fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        val value = byte.toInt() and 0xff
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0xf])
    }
}
