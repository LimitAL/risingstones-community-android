package top.cxmeow.risingstones.feature.personaldata.domain

import java.time.Instant

enum class PersonalDataBoard(val statusKeys: List<String>) {
    Frontline(listOf("pvp")),
    Ultimate((1..7).map { "jue$it" }),
    Fishing(listOf("fishing")),
    Savage(listOf("lingshi")),
    Glamour(listOf("vanity")),
}

data class PersonalDataIdentity(
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
) {
    val location: String get() = listOf(areaName, groupName).filter(String::isNotBlank).joinToString(" · ")
}

data class PersonalDataAvailability(val values: Map<String, String>) {
    fun hasData(board: PersonalDataBoard): Boolean? {
        val found = board.statusKeys.mapNotNull(values::get)
        if (found.isEmpty()) return null
        return found.any { it.trim().let { value -> value.isNotEmpty() && value != "0" } }
    }
}

data class PersonalDataMetric(
    val id: String,
    val value: String,
    val unit: PersonalDataMetricUnit? = null,
)

enum class PersonalDataMetricUnit { Times, Percent, Levels, Points, Pieces, Hours }

data class FrontlinePeriod(
    val kind: FrontlinePeriodKind,
    val metrics: List<PersonalDataMetric>,
)

enum class FrontlinePeriodKind(val wireValue: String) { Total("total"), Since51("v51"), Last30Days("30days") }

data class PersonalDataField(val key: String, val value: String)
data class PersonalDataEntry(val id: String, val title: String, val fields: List<PersonalDataField>)
data class PersonalDataSection(
    val id: String,
    val entries: List<PersonalDataEntry> = emptyList(),
    val error: String? = null,
)

data class PersonalDataBoardContent(
    val board: PersonalDataBoard,
    val metrics: List<PersonalDataMetric>,
    val sections: List<PersonalDataSection>,
    val frontlinePeriods: List<FrontlinePeriod> = emptyList(),
    val fetchedAt: Instant = Instant.now(),
)

data class UltimateEncounter(
    val territoryType: Int,
    val statusKey: String,
    val shortName: String,
    val title: String,
    val coverUrl: String,
)

object UltimateEncounterCatalog {
    val encounters = listOf(
        encounter(733, "jue1", "UCoB", "The Unending Coil of Bahamut"),
        encounter(777, "jue2", "UwU", "The Weapon's Refrain"),
        encounter(887, "jue3", "TEA", "The Epic of Alexander"),
        encounter(968, "jue4", "DSR", "Dragonsong's Reprise"),
        encounter(1122, "jue5", "TOP", "The Omega Protocol"),
        encounter(1238, "jue6", "FRU", "Futures Rewritten"),
        encounter(1363, "jue7", "Dancing Mad", "Dancing Mad"),
    )

    fun find(territoryType: Int) = encounters.firstOrNull { it.territoryType == territoryType }

    private fun encounter(id: Int, key: String, shortName: String, title: String): UltimateEncounter {
        val image = if (id == 1363) "default" else shortName
        return UltimateEncounter(
            id,
            key,
            shortName,
            title,
            "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/ultimate/$image.png",
        )
    }
}

data class UltimateEncounterSummary(
    val territoryType: Int,
    val clearTimes: Int,
    val entersBeforeFirstClear: Int?,
    val firstClearJobName: String?,
    val firstClearAt: Instant?,
    val firstClearElapsedSeconds: Long?,
    val deathsBeforeFirstClear: Int?,
)

data class UltimateDashboard(
    val summaries: List<UltimateEncounterSummary>,
    val fetchedAt: Instant = Instant.now(),
) {
    val totalClears: Int get() = summaries.sumOf(UltimateEncounterSummary::clearTimes)
    fun summary(territoryType: Int) = summaries.firstOrNull { it.territoryType == territoryType }
}

data class UltimateTeammate(
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val jobName: String,
)

data class UltimateJobStatistic(val jobName: String, val clearTimes: Int)
data class UltimatePartnerStatistic(
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val jointBattleTimes: Int,
)
data class UltimatePhaseProgress(val phase: String, val reachedAt: Instant?)
data class UltimateDeathPoint(val x: Double, val y: Double, val period: String, val occurredAt: Instant?)

data class UltimateEncounterDetail(
    val summary: UltimateEncounterSummary,
    val teammates: List<UltimateTeammate>,
    val jobs: List<UltimateJobStatistic>,
    val partners: List<UltimatePartnerStatistic>,
    val phases: List<UltimatePhaseProgress>,
    val deathPoints: List<UltimateDeathPoint>,
    val sectionErrors: Map<String, String> = emptyMap(),
)

data class FishKingCatalogEntry(val itemId: Int, val iconId: Int, val name: String, val patch: String)
data class SavageRaidCatalogEntry(val instanceId: Int, val name: String, val imageId: Int?)

data class SavageRaidCatalogTier(
    val nameEnglish: String,
    val nameChinese: String,
    val achievementOnly: Boolean,
    val achievementText: String?,
    val raids: List<SavageRaidCatalogEntry>,
)

data class SavageRaidCatalogSeries(
    val name: String,
    val abbreviation: String,
    val tiers: List<SavageRaidCatalogTier>,
)
data class GlamourCatalogSetItem(
    val slotIndex: Int,
    val itemId: Int,
    val name: String,
    val iconId: Int,
)

data class GlamourCatalogSet(
    val mirageSetId: Int,
    val name: String,
    val iconId: Int?,
    val items: List<GlamourCatalogSetItem>,
)

data class GlamourCatalogFashionAccessory(
    val id: Int,
    val iconId: Int,
    val name: String?,
)

data class GlamourCatalogStain(
    val stainId: Int,
    val name: String,
    val color: Long,
    val isMetallic: Boolean,
)

data class GlamourCatalogSummary(
    val setCount: Int,
    val fashionAccessoryCount: Int,
    val stainCount: Int,
    val sets: List<GlamourCatalogSet> = emptyList(),
    val fashionAccessories: List<GlamourCatalogFashionAccessory> = emptyList(),
    val stains: List<GlamourCatalogStain> = emptyList(),
)
data class PersonalDataOfficialCatalogs(
    val fish: Map<Int, FishKingCatalogEntry> = emptyMap(),
    val savageRaids: Map<Int, SavageRaidCatalogEntry> = emptyMap(),
    val glamour: GlamourCatalogSummary? = null,
    val savageSeries: List<SavageRaidCatalogSeries> = emptyList(),
)

fun interface PersonalDataCatalogProvider {
    suspend fun fetchCatalogs(): PersonalDataOfficialCatalogs
}

object EmptyPersonalDataCatalogProvider : PersonalDataCatalogProvider {
    override suspend fun fetchCatalogs() = PersonalDataOfficialCatalogs()
}

sealed class PersonalDataException(message: String) : Exception(message) {
    data object AuthenticationRequired : PersonalDataException("Rising Stones identity is required")
    data object MissingPayload : PersonalDataException("Personal data is unavailable")
    data class Business(val code: Int, val detail: String?) :
        PersonalDataException(detail?.takeIf(String::isNotBlank) ?: "Rising Stones error $code")
    data class UnsupportedBoard(val board: PersonalDataBoard) : PersonalDataException("Unsupported board: $board")
}

interface PersonalDataService {
    val hasCommunityIdentity: Boolean
    suspend fun fetchIdentity(): PersonalDataIdentity
    suspend fun fetchAvailability(): PersonalDataAvailability
    suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent
    suspend fun fetchUltimateDashboard(): UltimateDashboard
    suspend fun fetchUltimateEncounterDetail(summary: UltimateEncounterSummary): UltimateEncounterDetail
    suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs
}
