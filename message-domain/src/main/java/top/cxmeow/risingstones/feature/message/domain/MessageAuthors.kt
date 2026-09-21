package top.cxmeow.risingstones.feature.message.domain

/** The sender displayed by the official message header, independently of its content target. */
sealed interface MessageAuthorTarget {
    data object Self : MessageAuthorTarget
    data class Community(val uuid: String) : MessageAuthorTarget
}

data class MessageAuthorPage(
    val page: MessagePage,
    val authorsByKey: Map<String, MessageAuthorTarget>,
)

/** Optional metadata from the same explicit, potentially acknowledging message read. */
interface MessageAuthorService : MessageService {
    suspend fun readMessagesWithAuthors(query: MessageQuery): MessageAuthorPage
}
