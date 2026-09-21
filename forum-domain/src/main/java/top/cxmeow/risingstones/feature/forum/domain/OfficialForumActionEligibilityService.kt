package top.cxmeow.risingstones.feature.forum.domain

/**
 * Optional first-use eligibility for explicit forum actions.
 *
 * Eligibility only allows the caller to start a user-selected action. The capability properties
 * on [OfficialForumService] and [OfficialForumImageUploadService] continue to mean that the active
 * credential has already completed a verified official action.
 */
interface OfficialForumActionEligibilityService {
    val canAttemptAuthenticatedWrites: Boolean
    val canAttemptCommentImageUpload: Boolean
}
