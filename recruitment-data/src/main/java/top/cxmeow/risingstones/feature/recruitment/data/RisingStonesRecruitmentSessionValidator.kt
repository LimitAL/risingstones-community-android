package top.cxmeow.risingstones.feature.recruitment.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

/**
 * Probes the authenticated guild-recruitment list without changing server state.
 *
 * The probe grants only [RisingStonesCapability.RecruitmentAuthenticated]. Recruitment write
 * access requires separate explicit evidence and is never inferred from a successful read.
 */
class RisingStonesRecruitmentSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : RisingStonesSessionValidator {
    override suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer).let {
            it.copy(
                capabilities = it.capabilities -
                    RisingStonesCapability.RecruitmentAuthenticated,
            )
        }
        val path = "api/home/recruit/recruitGuildList"
        return try {
            val headers = linkedMapOf<String, String>()
            authorizer.authorize(
                context = RisingStonesRequestContext(
                    path = path,
                    requirement = RisingStonesAuthenticationRequirement.Required,
                    capability = RisingStonesCapability.RecruitmentAuthenticated,
                ),
                sink = RisingStonesHeaderSink { name, value -> headers[name] = value },
            )
            val httpResponse = client.execute(
                RisingStonesApiRequest(
                    path = path,
                    query = listOf(
                        RisingStonesApiQueryItem("page", "1"),
                        RisingStonesApiQueryItem("limit", "1"),
                    ),
                    headers = headers,
                ),
            )
            check(httpResponse.statusCode in 200..299)
            val response = json.parseToJsonElement(
                httpResponse.body.decodeToString(),
            ).jsonObject
            val data = response["data"] as? JsonObject
            if (
                RisingStonesResponsePolicy.accepts(response.intValue("code")) &&
                data?.get("rows") is JsonArray
            ) {
                base.copy(
                    capabilities = base.capabilities +
                        RisingStonesCapability.RecruitmentAuthenticated,
                )
            } else {
                base
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            base
        }
    }
}

private fun JsonObject.intValue(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
}
