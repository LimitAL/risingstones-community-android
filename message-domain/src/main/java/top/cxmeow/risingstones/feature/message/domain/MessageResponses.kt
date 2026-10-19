package top.cxmeow.risingstones.feature.message.domain

/** Official status controls entry; timestamps do not independently end a recruitment. */
data class MessageResponseStatus(val isRecruiting: Boolean)
data class MessageResponsePage(val page: MessagePage, val statusesByKey: Map<String, MessageResponseStatus>)

/** Additive contract: existing message/author page constructors and copy methods stay unchanged. */
interface MessageResponseService : MessageService {
    suspend fun readResponseMessages(query: MessageQuery): MessageResponsePage
}
