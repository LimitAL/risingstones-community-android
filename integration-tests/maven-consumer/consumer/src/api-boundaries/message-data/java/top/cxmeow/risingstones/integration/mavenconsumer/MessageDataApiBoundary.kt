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
