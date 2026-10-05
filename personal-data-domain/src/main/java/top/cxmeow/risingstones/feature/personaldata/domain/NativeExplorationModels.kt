package top.cxmeow.risingstones.feature.personaldata.domain

/** OfficialWeb remains the default; native queries follow the Apple client's reviewed contract. */
enum class ExplorationQueryProfile { OfficialWeb, AstriaNative }
enum class DeepDungeonType(val wireValue: String) { DD1("dd1"), DD2("dd2"), DD3("dd3"), DD4("dd4") }
data class ExplorationQuery(
    val profile: ExplorationQueryProfile = ExplorationQueryProfile.OfficialWeb,
    val deepDungeonType: DeepDungeonType = DeepDungeonType.DD4,
)

/** Optional extension: existing hosts and test services retain their original behavior. */
interface PersonalDataNativeExplorationService : PersonalDataExplorationService {
    suspend fun fetchExplorationOverview(board: ExplorationBoard, query: ExplorationQuery): ExplorationOverview
    suspend fun fetchExplorationHistory(board: ExplorationBoard, section: ExplorationSectionKind, query: ExplorationQuery): ExplorationSection
}
