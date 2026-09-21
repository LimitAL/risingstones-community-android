package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.cxmeow.risingstones.feature.personaldata.domain.*

/** One explicitly requested section maps to one reviewed full-array GET. */
internal class PersonalDataDashboardApiReader(
    private val reader: PersonalDataRequestReader,
    private val reading: PersonalDataReadingApiReader,
) {
    suspend fun fetch(section: PersonalDataDashboardSectionKind): PersonalDataDashboardData = when (section) {
        PersonalDataDashboardSectionKind.FishingSummary -> PersonalDataDashboardData.FishingSummary(
            rows("fishTotal1").firstOrNull()?.let { FishingOverview(
                it["total_times"].count(), it["succ_rate"].fraction(),
                it["sea_times"].count(), it["max_sea_score"].count(),
            ) })
        PersonalDataDashboardSectionKind.FishRanking -> PersonalDataDashboardData.FishRanking(
            reading.fishingRanking(PersonalDataFishingRankingKind.Fish))
        PersonalDataDashboardSectionKind.BaitRanking -> PersonalDataDashboardData.BaitRanking(
            reading.fishingRanking(PersonalDataFishingRankingKind.Bait))
        PersonalDataDashboardSectionKind.BigFish -> PersonalDataDashboardData.BigFish(
            mappedRows("fishBig4") { PersonalDataFishCatch(it.requiredName("catalog_name"),
                it["log_time"].date(), it["fish_num"].count()) })
        PersonalDataDashboardSectionKind.FishingAchievements -> PersonalDataDashboardData.FishingAchievements(
            mappedRows("fishAchieve5") { PersonalDataAchievementRecord(it.requiredId("achieve_id"),
                it["achieve_name"].text(), it["achieve_detail"].text(), it["log_time"].date()) })
        PersonalDataDashboardSectionKind.OceanFishing -> PersonalDataDashboardData.OceanFishing(
            mappedRows("fishSea6") { PersonalDataOceanRoute(it.requiredId("territory_type"),
                it["max_sea_score"].count(), it["sea_times"].count()) })
        PersonalDataDashboardSectionKind.GlamourSummary -> PersonalDataDashboardData.GlamourSummary(
            rows("getDressTotal7").firstOrNull()?.let { GlamourOverview(
                it["washing_num"].count(), it["color_times"].count(), it["vanity_times"].count(),
            ) })
        PersonalDataDashboardSectionKind.Races -> PersonalDataDashboardData.Races(
            mappedRows("getDressRace1") { PersonalDataRankedRace(personalDataRaceUsage(it), it["rate_rn"].count()) })
        PersonalDataDashboardSectionKind.Stains -> PersonalDataDashboardData.Stains(
            mappedRows("getDressColor2") { PersonalDataStainUsage(it.requiredId("catalog_id", allowZero = true),
                it["color_times"].count(), it["rn"].count()) })
        PersonalDataDashboardSectionKind.Accessories -> PersonalDataDashboardData.Accessories(
            mappedRows("getDressOrnament3") { PersonalDataAccessoryUsage(it.requiredId("ornament"),
                it["ornament_times"].count(), it["rn"].count()) })
        PersonalDataDashboardSectionKind.Vanity -> PersonalDataDashboardData.Vanity(
            mappedRows("getDressVanity4", ::vanity))
        PersonalDataDashboardSectionKind.Sets -> PersonalDataDashboardData.Sets(reading.glamourSetRecords())
        PersonalDataDashboardSectionKind.SavageSummary -> PersonalDataDashboardData.SavageSummary(
            rows("getLingShiTotal").firstOrNull()?.let { SavageOverview(it["territory_num"].count(),
                it["enter_num"].count(), it["finish_times"].count(), it["elapsed_time"].nonNegativeNumber()) })
        PersonalDataDashboardSectionKind.SavageRaids -> PersonalDataDashboardData.SavageRaids(
            mappedRows("getLingShi") { PersonalDataSavageClear(it.requiredId("territory_type"),
                it["log_time"].date(), when (it["no_limit"].count()) { 0L -> false; 1L -> true; else -> null },
                it["job_name"].text(), it["elapsed_time"].nonNegativeNumber()) })
    }

    private fun vanity(row: JsonObject): PersonalDataVanityUsage {
        val name = row["Name"].text()
        val itemId = row["vanity"].id()
        if (name == null && itemId == null) throw PersonalDataException.MissingPayload
        return PersonalDataVanityUsage(
            period = when (row["rank_type"].text()) {
                "total" -> PersonalDataVanityPeriod.AllTime
                "year" -> PersonalDataVanityPeriod.LastYear
                else -> PersonalDataVanityPeriod.Unknown
            },
            categoryId = row["dress_type"].id(), itemId = itemId, name = name,
            iconId = row["Icon"].id(), count = row["times"].count(),
        )
    }

    private suspend fun <T> mappedRows(endpoint: String, map: (JsonObject) -> T): List<T> =
        rows(endpoint).map { currentCoroutineContext().ensureActive(); map(it) }

    private suspend fun rows(endpoint: String): List<JsonObject> {
        val data = reader.read("api/home/dataCenter/$endpoint", emptyList())["data"] as? JsonArray
            ?: throw PersonalDataException.MissingPayload
        return data.map {
            currentCoroutineContext().ensureActive()
            it as? JsonObject ?: throw PersonalDataException.MissingPayload
        }
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }
    ?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.requiredName(key: String) = this[key].text() ?: throw PersonalDataException.MissingPayload
private fun JsonElement?.numberText() = (this as? JsonPrimitive)?.contentOrNull?.trim()
private fun JsonElement?.count(): Long? = try {
    numberText()?.toBigDecimalOrNull()?.longValueExact()?.takeIf { it >= 0 }
} catch (_: ArithmeticException) { null }
private fun JsonElement?.nonNegativeNumber(): Double? = numberText()?.toBigDecimalOrNull()?.toDouble()
    ?.takeIf { it.isFinite() && it >= 0.0 }
private fun JsonElement?.fraction() = nonNegativeNumber()?.takeIf { it <= 1.0 }
private fun JsonElement?.id(allowZero: Boolean = false): Int? = numberText()
    ?.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it >= if (allowZero) 0 else 1 }
private fun JsonObject.requiredId(key: String, allowZero: Boolean = false) =
    this[key].id(allowZero) ?: throw PersonalDataException.MissingPayload
private fun JsonElement?.date() = text()?.let(::personalDataRecordedInstant)
