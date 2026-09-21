package top.cxmeow.risingstones.feature.forum.domain

/** Optional authoring extension; legacy comment drafts and service implementations remain valid. */
interface OfficialForumCommentAuthoringService : OfficialForumService {
    val canReadMentionCandidates: Boolean
    suspend fun fetchMentionCandidates(): List<OfficialForumMentionCandidate>
    suspend fun submitCommentWithMentions(
        draft: OfficialForumCommentDraft,
        mentions: List<OfficialForumCommentMention>,
    ): List<Int>
}

data class OfficialForumCommentMention(val uuid: String, val name: String)

data class OfficialForumMentionCandidate(
    val uuid: String,
    val name: String,
    val avatarUrl: String? = null,
    val areaName: String = "",
    val groupName: String = "",
)

/** The official public comment editor configuration, verified on 2026-09-20. */
val OfficialForumCommentEmojiNumbers: IntRange = 1..46
