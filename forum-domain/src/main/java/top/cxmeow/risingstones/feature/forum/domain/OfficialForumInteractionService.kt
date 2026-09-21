package top.cxmeow.risingstones.feature.forum.domain

/** Optional detail metadata without changing the existing detail model or service contract. */
interface OfficialForumInteractionService : OfficialForumImageUploadService {
    suspend fun fetchPostInteraction(id: Int): OfficialForumPostInteraction
}

data class OfficialForumPostInteraction(
    val detail: OfficialForumPostDetail,
    /** Vote IDs whose results the official response exposes, independent of participation. */
    val voteResultsAvailable: Set<String>,
)
