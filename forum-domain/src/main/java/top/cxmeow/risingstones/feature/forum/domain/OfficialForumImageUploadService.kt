package top.cxmeow.risingstones.feature.forum.domain

/** Optional image capability; existing OfficialForumService implementations remain valid. */
interface OfficialForumImageUploadService : OfficialForumService {
    val canUploadCommentImages: Boolean
}
