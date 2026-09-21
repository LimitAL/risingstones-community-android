package top.cxmeow.risingstones.feature.recruitment.domain

/** Optional author comparison; unknown identity never grants permission to respond. */
interface RecruitmentResponseEligibilityService : RecruitmentInteractionService {
    suspend fun fetchDutyInteractionDetail(id: Int): DutyRecruitmentInteractionDetail
    suspend fun fetchCommunityInteractionDetail(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentInteractionDetail
}

data class DutyRecruitmentInteractionDetail(
    val detail: DutyRecruitmentDetail,
    val isCurrentUserAuthor: Boolean?,
)

data class CommunityRecruitmentInteractionDetail(
    val detail: CommunityRecruitmentDetail,
    val isCurrentUserAuthor: Boolean?,
)
