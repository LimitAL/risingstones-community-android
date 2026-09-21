package top.cxmeow.risingstones.feature.message.domain

import java.time.Instant

enum class MessageCategory { System, Mentions, Comments, Likes, Recruitment, Responses }
enum class MessageCommentChannel { Received, Sent }
enum class MessageRecruitmentChannel { Beginner, Duty, Guild, Other }
enum class MessageTargetKind { Post, Guide, Dynamic, Glamour, Recruitment, RolePlayRecruitment, GuildPhoto }

data class MessageQuery(
    val category: MessageCategory,
    val page: Int = 1,
    val commentChannel: MessageCommentChannel = MessageCommentChannel.Received,
    val recruitmentChannel: MessageRecruitmentChannel = MessageRecruitmentChannel.Duty,
)

data class MessageTarget(
    val kind: MessageTargetKind,
    val id: Int,
    val recruitmentChannel: MessageRecruitmentChannel? = null,
)

data class CommunityMessage(
    val key: String,
    val category: MessageCategory,
    val authorName: String,
    val authorLocation: String,
    val title: String,
    val contentHtml: String,
    val contextHtml: String,
    val imageUrls: List<String>,
    val createdAt: Instant?,
    val target: MessageTarget?,
    val officialLink: String?,
    val contactInformation: String?,
)

data class MessagePage(val items: List<CommunityMessage>, val page: Int, val hasMore: Boolean)

data class MessageUnreadSummary(
    val system: Int,
    val mentions: Int,
    val comments: Int,
    val likes: Int,
    val recruitment: Int,
    val recruitmentByChannel: Map<MessageRecruitmentChannel, Int>,
) {
    fun count(category: MessageCategory): Int = when (category) {
        MessageCategory.System -> system
        MessageCategory.Mentions -> mentions
        MessageCategory.Comments -> comments
        MessageCategory.Likes -> likes
        MessageCategory.Recruitment -> recruitment
        MessageCategory.Responses -> 0
    }
}

interface MessageService {
    val canRead: Boolean
    /** Read-only: safe for capability validation and explicit summary refresh. */
    suspend fun fetchUnreadSummary(): MessageUnreadSummary
    /** May mark this category as read. Call only for an explicit user navigation or refresh. */
    suspend fun readMessages(query: MessageQuery): MessagePage
}

sealed class MessageException(message: String) : Exception(message) {
    data object AuthenticationRequired : MessageException("Message authentication required")
    data object Unavailable : MessageException("Message capability unavailable")
    data object InvalidResponse : MessageException("Invalid message response")
    data object Network : MessageException("Message transport failed")
    class Business(val code: Int?) : MessageException("Message request failed")
}
