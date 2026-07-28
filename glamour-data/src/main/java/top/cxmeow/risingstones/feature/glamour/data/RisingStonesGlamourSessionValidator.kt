package top.cxmeow.risingstones.feature.glamour.data

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
 * Adds the authenticated glamour capability only after a non-mutating production-shaped list
 * request succeeds with the current credential.
 *
 * A failed optional probe does not reject an otherwise valid Rising Stones session. It simply
 * leaves the capability absent, so hosts keep the glamour entry hidden.
 */
class RisingStonesGlamourSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val temporarySessionId: String = UUID.randomUUID().toString(),
) : RisingStonesSessionValidator {
    override suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer)
        val path = "api/home/glamour/glamoursList"
        return try {
            val headers = linkedMapOf<String, String>()
            authorizer.authorize(
                context = RisingStonesRequestContext(
                    path = path,
                    requirement = RisingStonesAuthenticationRequirement.Required,
                    capability = RisingStonesCapability.GlamourAuthenticated,
                ),
                sink = RisingStonesHeaderSink { name, value -> headers[name] = value },
            )
            val response = json.parseToJsonElement(
                client.execute(
                    RisingStonesApiRequest(
                        path = path,
                        query = listOf(
                            RisingStonesApiQueryItem("page", "1"),
                            RisingStonesApiQueryItem("limit", "1"),
                            RisingStonesApiQueryItem("tempsuid", temporarySessionId),
                        ),
                        headers = headers,
                    ),
                ).body.decodeToString(),
            ).jsonObject
            if (response.intValue("code") == 10000 && response["data"] is JsonObject) {
                base.copy(
                    capabilities = base.capabilities +
                        RisingStonesCapability.GlamourAuthenticated,
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
