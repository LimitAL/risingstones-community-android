package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.account.data.RisingStonesAccountApiService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountService
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

fun accountServiceFromPublishedArtifact(
    client: RisingStonesPublicApiClient,
    sessionProvider: RisingStonesSessionProvider,
    json: Json,
): RisingStonesAccountService = RisingStonesAccountApiService(
    client = client,
    sessionProvider = sessionProvider,
    json = json,
)

fun accountActionVerificationFromPublishedArtifact(
    client: RisingStonesPublicApiClient,
    provider: RisingStonesSessionProvider,
): top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountActionVerificationService =
    RisingStonesAccountApiService(client, provider)

fun characterAvatarFromPublishedArtifact(
    character: top.cxmeow.risingstones.feature.account.domain.RisingStonesCharacter,
): String? = character.avatarUrl

fun medalWallFromPublishedArtifact(dashboard: top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountDashboard): top.cxmeow.risingstones.feature.account.domain.RisingStonesMedalWall? = dashboard.medalWall
