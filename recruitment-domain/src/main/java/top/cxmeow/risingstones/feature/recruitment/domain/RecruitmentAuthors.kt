package top.cxmeow.risingstones.feature.recruitment.domain

data class CommunityRecruitmentAuthorPage(
    val page: CommunityRecruitmentPage,
    val authorUuids: Map<Int, String>,
)

data class CommunityRecruitmentAuthorDetail(
    val interaction: CommunityRecruitmentInteractionDetail,
    val authorUuid: String?,
)

data class RolePlayReviewAuthorPage(
    val page: RolePlayRecruitmentReviewPage,
    val authorUuids: Map<String, String>,
    val previewAuthorUuids: Map<String, Map<String, String>> = emptyMap(),
)

data class RolePlaySubcommentAuthorPage(
    val page: RolePlayRecruitmentSubcommentPage,
    val authorUuids: Map<String, String>,
)

/** Optional community author metadata; record IDs and fictional RP members are not authors. */
interface RecruitmentAuthorService : DutyRecruitmentService {
    suspend fun fetchCommunityRecruitmentsWithAuthors(query: CommunityRecruitmentQuery): CommunityRecruitmentAuthorPage
    suspend fun fetchCommunityDetailWithAuthor(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentAuthorDetail
    suspend fun fetchRolePlayReviewsWithAuthors(query: RolePlayRecruitmentReviewQuery): RolePlayReviewAuthorPage
    suspend fun fetchRolePlaySubcommentsWithAuthors(rootParentId: String, page: Int, limit: Int): RolePlaySubcommentAuthorPage
}
