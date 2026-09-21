package top.cxmeow.risingstones.feature.personaldata.domain

enum class ExplorationBoard { OccultCrescent, DeepDungeon }
enum class ExplorationSectionKind {
    Overview, PhantomJobs, ItemUsage, AcquiredItems, TreasureChests, Achievements, Aether,
    Challenges, ChallengeJobs, FailureFloors, SpecialBattle, SpecialBattleJobs, DeathLocations, FirstClearTeam,
    TreasureHistory, RelicHistory, ItemHistory,
}
enum class ExplorationFieldKind {
    KnowledgeLevel, Fates, CriticalEncounters, SilverCoins, GoldCoins, WhiteSilverCoins, WhiteGoldCoins,
    PhantomJob, Level, Quantity, ItemName, ItemCategory, FirstAcquiredAt, Uses, BoxType, BoxGrade,
    AetherColor, AetherPoints, AchievementName, RecordedAt, Solo, WeaponLevel, ArmorLevel, Clears,
    FirstClearAt, ClearDuration, Attempts, Deaths, Wipes, ClassJob, CharacterName, World, Area, Floor, X, Y,
}
data class ExplorationField(val kind: ExplorationFieldKind, val value: String)
data class ExplorationRecord(
    val key: String,
    val title: String,
    val fields: List<ExplorationField>,
    val itemId: Int? = null,
    val achievementId: Int? = null,
    val territoryId: Int? = null,
)
enum class ExplorationFailure { Network, InvalidResponse, Business }
data class ExplorationSection(
    val kind: ExplorationSectionKind,
    val records: List<ExplorationRecord> = emptyList(),
    val failure: ExplorationFailure? = null,
)
data class ExplorationOverview(
    val board: ExplorationBoard,
    val available: Boolean,
    val metrics: List<ExplorationField>,
    val sections: List<ExplorationSection>,
)

/** Optional extension; existing PersonalDataService implementations need no new methods. */
interface PersonalDataExplorationService : PersonalDataService {
    suspend fun fetchExplorationOverview(board: ExplorationBoard): ExplorationOverview
    suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind): ExplorationSection
}

sealed class ExplorationException(message: String) : Exception(message) {
    data object AuthenticationRequired : ExplorationException("Exploration authentication required")
    data object Unavailable : ExplorationException("Exploration capability unavailable")
    data object Network : ExplorationException("Exploration transport failed")
    data object InvalidResponse : ExplorationException("Invalid exploration response")
    class Business(val code: Int) : ExplorationException("Exploration request failed")
}

/** Identifier and level cap facts from the official mobile page; names belong to localized UI resources. */
object PhantomJobCatalog {
    val levelCaps: Map<Int, Int> = mapOf(0 to 0, 1 to 6, 2 to 3, 3 to 6, 4 to 6, 5 to 5, 6 to 4,
        7 to 5, 8 to 5, 9 to 6, 10 to 4, 11 to 5, 12 to 6, 13 to 4, 14 to 4, 15 to 4, 16 to 6,
        17 to 5, 18 to 5, 19 to 4, 20 to 5, 21 to 3, 22 to 6, 23 to 5)
}

fun ExplorationBoard.sections(): List<ExplorationSectionKind> = when (this) {
    ExplorationBoard.OccultCrescent -> listOf(ExplorationSectionKind.Overview, ExplorationSectionKind.PhantomJobs,
        ExplorationSectionKind.ItemUsage, ExplorationSectionKind.AcquiredItems, ExplorationSectionKind.TreasureChests,
        ExplorationSectionKind.Aether, ExplorationSectionKind.Achievements, ExplorationSectionKind.TreasureHistory,
        ExplorationSectionKind.RelicHistory)
    ExplorationBoard.DeepDungeon -> listOf(ExplorationSectionKind.Challenges, ExplorationSectionKind.ChallengeJobs,
        ExplorationSectionKind.FailureFloors,
        ExplorationSectionKind.SpecialBattle, ExplorationSectionKind.SpecialBattleJobs, ExplorationSectionKind.FirstClearTeam,
        ExplorationSectionKind.AcquiredItems, ExplorationSectionKind.Achievements, ExplorationSectionKind.DeathLocations,
        ExplorationSectionKind.TreasureHistory, ExplorationSectionKind.ItemHistory)
}

val ExplorationSectionKind.isHistory: Boolean
    get() = this in listOf(ExplorationSectionKind.TreasureHistory, ExplorationSectionKind.RelicHistory,
        ExplorationSectionKind.ItemHistory)
