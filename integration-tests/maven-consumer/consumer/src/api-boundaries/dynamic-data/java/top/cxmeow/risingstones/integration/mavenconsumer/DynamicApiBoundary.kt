package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.feature.dynamic.data.DynamicApiService
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicListQuery

suspend fun readPublishedDynamic(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider) =
    DynamicApiService(client, session, Json).fetchFeed(DynamicListQuery())
