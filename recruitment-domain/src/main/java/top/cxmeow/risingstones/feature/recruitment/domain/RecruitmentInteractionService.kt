package top.cxmeow.risingstones.feature.recruitment.domain

/** Optional write capability without changing the existing recruitment service contract. */
interface RecruitmentInteractionService : DutyRecruitmentService {
    val canPerformAuthenticatedWrites: Boolean
}
