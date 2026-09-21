package top.cxmeow.risingstones.feature.dynamic.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesDynamicSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RisingStonesSessionValidator {
    override suspend fun validateSession(authorizer: RisingStonesRequestAuthorizer): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer).let {
            it.copy(capabilities = it.capabilities - RisingStonesCapability.DynamicRead)
        }
        return try {
            val data = readDynamicPayload(client, json, authorizer, "getFollowDynamicList",
                listOf(q("page", 1), q("limit", 1)))
            if (data["rows"] is JsonArray) base.copy(capabilities = base.capabilities + RisingStonesCapability.DynamicRead)
            else base
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { base }
    }
}
