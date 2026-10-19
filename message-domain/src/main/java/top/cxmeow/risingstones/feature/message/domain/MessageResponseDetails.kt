package top.cxmeow.risingstones.feature.message.domain

import java.time.Instant

/** Publisher is independent of the responder and of whether recruitment has ended. */
data class MessageResponsePublisher(val uuid: String, val name: String, val location: String?, val avatarUrl: String?)
enum class MessageResponseIdentity { LookingForNewcomer, LookingForMentor }
data class MessageResponseMetadata(
    val publisher: MessageResponsePublisher?,
    val identity: MessageResponseIdentity?,
    val badge: String?,
    val details: List<String>,
    val tags: List<String>,
    val targetArea: String?,
    val endsAt: Instant?,
    val responseCount: Int?,
)
data class MessageResponseDetailsPage(val response: MessageResponsePage, val metadataByKey: Map<String, MessageResponseMetadata>)

/** Additive: old MessageResponsePage/Status and CommunityMessage constructors remain unchanged. */
interface MessageResponseDetailsService : MessageResponseService {
    suspend fun readResponseDetails(query: MessageQuery): MessageResponseDetailsPage
}
