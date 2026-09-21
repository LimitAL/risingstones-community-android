package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictHandler
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictState
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumImageUploadService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumInteractionService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostInteraction
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVote
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentAuthoringService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentMention
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumMentionCandidate
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentEmojiNumbers
import top.cxmeow.risingstones.feature.forum.domain.deadline
import java.time.Instant

class ForumDomainApiBoundary(
    handler: OfficialForumIdentityConflictHandler,
) {
    val state: StateFlow<OfficialForumIdentityConflictState> = handler.identityConflictState
}


suspend fun browsePublishedForum(
    service: top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsingService,
): top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsePage = service.fetchBrowsePage(
    top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowseQuery(),
)

suspend fun readPublishedForumInteraction(
    service: OfficialForumInteractionService,
    postId: Int,
): OfficialForumPostInteraction = service.fetchPostInteraction(postId)

fun publishedForumImageCapability(service: OfficialForumImageUploadService): Boolean =
    service.canUploadCommentImages

fun publishedForumVoteDeadline(vote: OfficialForumPostVote): Instant? = vote.deadline()

suspend fun publishedMentionCandidates(service: OfficialForumCommentAuthoringService): List<OfficialForumMentionCandidate> =
    if (service.canReadMentionCandidates) service.fetchMentionCandidates() else emptyList()

suspend fun submitPublishedMentionComment(service: OfficialForumCommentAuthoringService,
    draft: OfficialForumCommentDraft, mentions: List<OfficialForumCommentMention>): List<Int> =
    service.submitCommentWithMentions(draft, mentions)

fun publishedCommentEmojiNumbers(): IntRange = OfficialForumCommentEmojiNumbers

fun publishedForumActionEligibility(
    service: top.cxmeow.risingstones.feature.forum.domain.OfficialForumActionEligibilityService,
): Pair<Boolean, Boolean> = service.canAttemptAuthenticatedWrites to service.canAttemptCommentImageUpload
