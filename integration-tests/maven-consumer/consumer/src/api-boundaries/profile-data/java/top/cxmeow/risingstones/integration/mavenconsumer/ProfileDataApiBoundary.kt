package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.profile.data.ProfileApiService
import top.cxmeow.risingstones.feature.profile.domain.CommunityProfile
import top.cxmeow.risingstones.feature.profile.domain.ProfileOwner
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

suspend fun readPublishedProfile(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): CommunityProfile =
    ProfileApiService(client, session, Json { ignoreUnknownKeys = true }).fetchProfile(ProfileOwner.Self)
