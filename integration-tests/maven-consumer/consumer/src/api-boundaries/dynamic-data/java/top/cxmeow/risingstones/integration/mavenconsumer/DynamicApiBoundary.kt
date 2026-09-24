package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.feature.dynamic.data.DynamicApiService
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicListQuery

suspend fun readPublishedDynamic(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider) =
    DynamicApiService(client, session, Json).fetchFeed(DynamicListQuery())

fun publishedDynamicActions(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider):
    top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionService = DynamicApiService(client, session)

fun publishedDynamicImages(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider):
    top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadService =
    top.cxmeow.risingstones.feature.dynamic.data.DynamicImageUploadApiService(client, session)

fun publishedDynamicPublishing(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider):
    top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingService = DynamicApiService(client, session)

fun publishedDynamicRecruitmentRelay(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider):
    top.cxmeow.risingstones.feature.dynamic.domain.DynamicRecruitmentRelayService = DynamicApiService(client, session)

fun publishedDynamicPublishingImages(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider):
    top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingImageUploadService =
    top.cxmeow.risingstones.feature.dynamic.data.DynamicImageUploadApiService(client, session)

suspend fun publishedDynamicComment(
    actions: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionService,
    scope: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope,
    draft: top.cxmeow.risingstones.feature.dynamic.domain.DynamicCommentDraft,
) = actions.comment(scope, draft)

suspend fun publishDynamic(
    publishing: top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingService,
    scope: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope,
    draft: top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishDraft,
) = publishing.publish(scope, draft)

suspend fun relayForumPostToDynamic(
    publishing: top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingService,
    scope: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope,
    draft: top.cxmeow.risingstones.feature.dynamic.domain.DynamicPostRelayDraft,
) = publishing.relayPost(scope, draft)

fun publishedDynamicRecruitmentRelayDraft(
    recruitmentId: Int,
    origin: top.cxmeow.risingstones.feature.dynamic.domain.DynamicOrigin,
) = top.cxmeow.risingstones.feature.dynamic.domain.DynamicRecruitmentRelayDraft(recruitmentId, origin)

suspend fun relayRecruitmentToDynamic(
    relay: top.cxmeow.risingstones.feature.dynamic.domain.DynamicRecruitmentRelayService,
    scope: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope,
    draft: top.cxmeow.risingstones.feature.dynamic.domain.DynamicRecruitmentRelayDraft,
) = relay.relayRecruitment(scope, draft)

suspend fun uploadDynamicPublishingImage(
    images: top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingImageUploadService,
    scope: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope,
    input: top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadInput,
) = images.uploadPublishingImage(scope, input)
