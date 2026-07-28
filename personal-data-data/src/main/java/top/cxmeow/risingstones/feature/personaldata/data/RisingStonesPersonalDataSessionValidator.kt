package top.cxmeow.risingstones.feature.personaldata.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

/**
 * Adds [RisingStonesCapability.PersonalData] only after the active credential can read both
 * non-mutating root endpoints used by the personal-data hub.
 *
 * This is an optional capability probe: an unavailable endpoint or an unbound character keeps the
 * otherwise valid Rising Stones session active and leaves only the personal-data entry hidden.
 */
class RisingStonesPersonalDataSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val temporarySessionId: String = UUID.randomUUID().toString(),
) : RisingStonesSessionValidator {
    override suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer)
        return try {
            val identity = probe(
                path = "api/home/groupAndRole/getCharacterBindInfo",
                authorizer = authorizer,
                extraQuery = listOf(RisingStonesApiQueryItem("platform", "2")),
            )
            val availability = probe(
                path = "api/home/dataCenter/dataOpenStatus",
                authorizer = authorizer,
            )
            if (identity["data"] is JsonObject && availability["data"] is JsonObject) {
                base.copy(capabilities = base.capabilities + RisingStonesCapability.PersonalData)
            } else {
                base
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            base
        }
    }

    private suspend fun probe(
        path: String,
        authorizer: RisingStonesRequestAuthorizer,
        extraQuery: List<RisingStonesApiQueryItem> = emptyList(),
    ): JsonObject {
        val headers = linkedMapOf<String, String>()
        authorizer.authorize(
            context = RisingStonesRequestContext(
                path = path,
                requirement = RisingStonesAuthenticationRequirement.Required,
                capability = RisingStonesCapability.PersonalData,
            ),
            sink = RisingStonesHeaderSink { name, value -> headers[name] = value },
        )
        val response = json.parseToJsonElement(
            client.execute(
                RisingStonesApiRequest(
                    path = path,
                    query = extraQuery +
                        RisingStonesApiQueryItem("tempsuid", temporarySessionId),
                    headers = headers,
                ),
            ).body.decodeToString(),
        ).jsonObject
        check(response.intValue("code") == 10000)
        return response
    }
}

private fun JsonObject.intValue(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
}
