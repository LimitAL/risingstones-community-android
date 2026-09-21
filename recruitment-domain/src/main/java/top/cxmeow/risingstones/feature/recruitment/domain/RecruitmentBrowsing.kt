package top.cxmeow.risingstones.feature.recruitment.domain

/** Optional complete filters and review ordering; existing services remain valid. */
interface RecruitmentBrowsingService : RecruitmentInteractionService {
    suspend fun fetchDutyRecruitments(query: DutyRecruitmentBrowseQuery): DutyRecruitmentListPage
    suspend fun fetchDutyFilterCatalog(): DutyRecruitmentFilterCatalog
    suspend fun fetchRolePlayReviews(query: RolePlayRecruitmentReviewQuery): RolePlayRecruitmentReviewPage
}

data class DutyRecruitmentBrowseQuery(
    val list: DutyRecruitmentListQuery = DutyRecruitmentListQuery(),
    val positions: List<DutyRecruitmentPosition> = listOfNotNull(list.position),
    val teamComposition: String = "",
    val targetAreaId: String = "",
    val labelIds: List<String> = emptyList(),
    val allianceTeamKey: String = "",
)

data class DutyRecruitmentFilterCatalog(
    val catalogs: DutyRecruitmentCatalogs,
    val labels: List<DutyRecruitmentLabel>,
    val areas: List<CommunityRecruitmentArea>,
)

enum class RolePlayRecruitmentReviewOrder(val wireValue: String) {
    Latest("latest"), Hottest("hottest"), ScoreDescending("scoreDesc"), ScoreAscending("scoreAsc"),
}

data class RolePlayRecruitmentReviewQuery(
    val recruitmentId: Int,
    val page: Int = 1,
    val limit: Int = 10,
    val order: RolePlayRecruitmentReviewOrder = RolePlayRecruitmentReviewOrder.Latest,
)

const val RecruitmentContactMaximumLength: Int = 30
