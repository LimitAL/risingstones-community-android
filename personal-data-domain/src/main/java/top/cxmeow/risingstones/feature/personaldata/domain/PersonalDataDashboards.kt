package top.cxmeow.risingstones.feature.personaldata.domain

import java.time.Instant

/** Optional, independently retriable semantic sections for the three catalog-backed boards. */
interface PersonalDataDashboardService : PersonalDataReadingService {
    suspend fun fetchDashboardSection(section: PersonalDataDashboardSectionKind): PersonalDataDashboardData
    suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs = PersonalDataSupplementaryCatalogs()
    fun raidImageUrl(imageId: Int): String? = null
    fun achievementIconUrl(iconId: Int): String? = null
}

enum class PersonalDataDashboardSectionKind(val board: PersonalDataBoard) {
    FishingSummary(PersonalDataBoard.Fishing), FishRanking(PersonalDataBoard.Fishing),
    BaitRanking(PersonalDataBoard.Fishing), BigFish(PersonalDataBoard.Fishing),
    FishingAchievements(PersonalDataBoard.Fishing), OceanFishing(PersonalDataBoard.Fishing),
    GlamourSummary(PersonalDataBoard.Glamour), Races(PersonalDataBoard.Glamour),
    Stains(PersonalDataBoard.Glamour), Accessories(PersonalDataBoard.Glamour),
    Vanity(PersonalDataBoard.Glamour), Sets(PersonalDataBoard.Glamour),
    SavageSummary(PersonalDataBoard.Savage), SavageRaids(PersonalDataBoard.Savage);

    companion object {
        fun forBoard(board: PersonalDataBoard) = entries.filter { it.board == board }
    }
}

sealed interface PersonalDataDashboardData {
    data class FishingSummary(val record: FishingOverview?) : PersonalDataDashboardData
    data class FishRanking(val rows: List<PersonalDataFishingRank>) : PersonalDataDashboardData
    data class BaitRanking(val rows: List<PersonalDataFishingRank>) : PersonalDataDashboardData
    data class BigFish(val rows: List<PersonalDataFishCatch>) : PersonalDataDashboardData
    data class FishingAchievements(val rows: List<PersonalDataAchievementRecord>) : PersonalDataDashboardData
    data class OceanFishing(val rows: List<PersonalDataOceanRoute>) : PersonalDataDashboardData
    data class GlamourSummary(val record: GlamourOverview?) : PersonalDataDashboardData
    data class Races(val rows: List<PersonalDataRankedRace>) : PersonalDataDashboardData
    data class Stains(val rows: List<PersonalDataStainUsage>) : PersonalDataDashboardData
    data class Accessories(val rows: List<PersonalDataAccessoryUsage>) : PersonalDataDashboardData
    data class Vanity(val rows: List<PersonalDataVanityUsage>) : PersonalDataDashboardData
    data class Sets(val rows: List<PersonalDataGlamourSetRecord>) : PersonalDataDashboardData
    data class SavageSummary(val record: SavageOverview?) : PersonalDataDashboardData
    data class SavageRaids(val rows: List<PersonalDataSavageClear>) : PersonalDataDashboardData
}

data class FishingOverview(val casts: Long?, val successRate: Double?, val seaTrips: Long?, val highestSeaScore: Long?)
data class GlamourOverview(val fantasiaUses: Long?, val dyesUsed: Long?, val projections: Long?)
/** Cumulative time is in hours; individual raid times below are in seconds. */
data class SavageOverview(val territoriesCleared: Long?, val entries: Long?, val clears: Long?, val elapsedHours: Double?)
data class PersonalDataFishCatch(val name: String, val caughtAt: Instant?, val count: Long?)
data class PersonalDataAchievementRecord(val achievementId: Int, val name: String?, val detail: String?, val obtainedAt: Instant?)
/** Territory 900 is nearshore and 1163 is offshore. Unknown territories retain their number. */
data class PersonalDataOceanRoute(val territoryId: Int, val highestScore: Long?, val trips: Long?)
data class PersonalDataRankedRace(val usage: PersonalDataRaceUsage, val rank: Long?)
data class PersonalDataStainUsage(val stainId: Int, val count: Long?, val rank: Long?)
data class PersonalDataAccessoryUsage(val accessoryId: Int, val count: Long?, val rank: Long?)
enum class PersonalDataVanityPeriod { AllTime, LastYear, Unknown }
data class PersonalDataVanityUsage(
    val period: PersonalDataVanityPeriod,
    val categoryId: Int?,
    val itemId: Int?,
    val name: String?,
    val iconId: Int?,
    val count: Long?,
)
data class PersonalDataSavageClear(
    val territoryId: Int,
    val clearedAt: Instant?,
    val supportsUnrestricted: Boolean?,
    val jobName: String?,
    val elapsedSeconds: Double?,
)

/** Additional public catalogs without changing the original catalog constructor or decoder. */
data class PersonalDataSupplementaryCatalogs(
    val oceanFish: List<PersonalDataOceanFishCatalogEntry> = emptyList(),
    val fishingAchievements: List<PersonalDataAchievementCatalogEntry> = emptyList(),
    val frontlineAchievements: List<PersonalDataAchievementCatalogEntry> = emptyList(),
    val vanityCategories: List<PersonalDataVanityCategory> = emptyList(),
)
data class PersonalDataOceanFishCatalogEntry(val itemId: Int, val iconId: Int, val name: String)
data class PersonalDataAchievementCatalogEntry(val achievementId: Int, val name: String, val detail: String?, val iconId: Int?)
data class PersonalDataVanityCategory(
    val id: Int,
    val name: String,
    val iconId: Int?,
    val majorOrder: Int,
    val minorOrder: Int,
    val selectable: Boolean = true,
)
interface PersonalDataSupplementaryCatalogProvider {
    suspend fun fetchSupplementaryCatalogs(): PersonalDataSupplementaryCatalogs
}
