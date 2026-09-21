package top.cxmeow.risingstones.feature.recruitment.domain

/**
 * Optional first-use eligibility for a user-selected recruitment write.
 *
 * This is only permission to attempt the exact action. It does not mean that
 * [RecruitmentInteractionService.canPerformAuthenticatedWrites] has been verified.
 */
interface RecruitmentActionEligibilityService {
    val canAttemptAuthenticatedWrites: Boolean
}
