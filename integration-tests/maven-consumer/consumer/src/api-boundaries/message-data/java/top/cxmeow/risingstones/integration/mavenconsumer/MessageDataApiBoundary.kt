package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.message.data.MessageApiService
import top.cxmeow.risingstones.feature.message.domain.MessageUnreadSummary
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

suspend fun readPublishedMessageSummary(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): MessageUnreadSummary =
    MessageApiService(client, session, Json { ignoreUnknownKeys = true }).fetchUnreadSummary()

suspend fun readPublishedMessageAuthors(service: top.cxmeow.risingstones.feature.message.domain.MessageAuthorService,
    query: top.cxmeow.risingstones.feature.message.domain.MessageQuery): Map<String,
    top.cxmeow.risingstones.feature.message.domain.MessageAuthorTarget> = service.readMessagesWithAuthors(query).authorsByKey

fun publishedCommentMetadata(message: top.cxmeow.risingstones.feature.message.domain.CommunityMessage):
    top.cxmeow.risingstones.feature.message.domain.MessageCommentMetadata? = message.comment

fun publishedReplyDestination(metadata: top.cxmeow.risingstones.feature.message.domain.MessageCommentMetadata):
    top.cxmeow.risingstones.feature.message.domain.MessageCommentReply? = metadata.reply

/** Kept precompiled for the local ae82 -> candidate runtime linkage probe. */
fun publishedMessageDefaultAndCopyAbi(): top.cxmeow.risingstones.feature.message.domain.CommunityMessage {
    val old = top.cxmeow.risingstones.feature.message.domain.CommunityMessage(
        "abi", top.cxmeow.risingstones.feature.message.domain.MessageCategory.System,
        "", "", "before", "", "", emptyList(), null, null, null, null)
    return old.copy(title = "ABI retained")
}

suspend fun readPublishedResponses(service: top.cxmeow.risingstones.feature.message.domain.MessageResponseService,
    query: top.cxmeow.risingstones.feature.message.domain.MessageQuery): top.cxmeow.risingstones.feature.message.domain.MessageResponsePage =
    service.readResponseMessages(query)

fun publishedResponseStatus(page: top.cxmeow.risingstones.feature.message.domain.MessageResponsePage, key: String): Boolean? =
    page.statusesByKey[key]?.isRecruiting

/** New details and legacy response constructors coexist in the published graph. */
suspend fun readPublishedResponseDetails(service: top.cxmeow.risingstones.feature.message.domain.MessageResponseDetailsService,
    query: top.cxmeow.risingstones.feature.message.domain.MessageQuery): top.cxmeow.risingstones.feature.message.domain.MessageResponsePublisher? {
    val details=service.readResponseDetails(query)
    val legacy=top.cxmeow.risingstones.feature.message.domain.MessageResponsePage(details.response.page,details.response.statusesByKey)
    val copy=legacy.copy(page=legacy.page)
    return copy.page.items.firstOrNull()?.key?.let { details.metadataByKey[it]?.publisher }
}
