package top.cxmeow.risingstones.feature.message.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesMessageSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RisingStonesSessionValidator {
    override suspend fun validateSession(authorizer: RisingStonesRequestAuthorizer): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer).let {
            it.copy(capabilities = it.capabilities - RisingStonesCapability.MessageRead)
        }
        return try {
            unreadSummary(requestMessagePayload(client, json, authorizer, "getTip"))
            base.copy(capabilities = base.capabilities + RisingStonesCapability.MessageRead)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { base }
    }
}
