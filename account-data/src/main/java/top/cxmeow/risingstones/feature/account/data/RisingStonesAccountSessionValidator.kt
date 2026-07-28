package top.cxmeow.risingstones.feature.account.data

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
import top.cxmeow.risingstones.network.RisingStonesApiException
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

/**
 * Validates both the browser session and the non-mutating account-read capability.
 *
 * Daily sign-in is deliberately not granted here because probing that endpoint would itself
 * mutate the user's account.
 */
class RisingStonesAccountSessionValidator(
    private val baseValidator: RisingStonesSessionValidator,
    private val client: RisingStonesPublicApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : RisingStonesSessionValidator {
    override suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        val base = baseValidator.validateSession(authorizer)
        val path = "api/home/userInfo/getUserInfo"
        val headers = linkedMapOf<String, String>()
        authorizer.authorize(
            context = RisingStonesRequestContext(
                path = path,
                requirement = RisingStonesAuthenticationRequirement.Required,
                capability = RisingStonesCapability.AccountRead,
            ),
            sink = RisingStonesHeaderSink { name, value -> headers[name] = value },
        )
        val response = json.parseToJsonElement(
            client.execute(
                RisingStonesApiRequest(
                    path = path,
                    headers = headers,
                ),
            ).body.decodeToString(),
        ).jsonObject
        val code = response.intValue("code")
        if (code != 10000) {
            throw RisingStonesApiException(
                message = response.stringValue("msg", "message")
                    ?: "Rising Stones account-read validation failed",
                code = code,
            )
        }
        val displayName = response.objectValue("data")
            ?.firstNestedString(setOf("character_name", "characterName"))
            ?: base.displayName
        return base.copy(
            displayName = displayName,
            capabilities = base.capabilities + RisingStonesCapability.AccountRead,
        )
    }
}

private fun JsonObject.stringValue(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    }

private fun JsonObject.intValue(vararg names: String): Int? =
    names.firstNotNullOfOrNull { name ->
        val primitive = this[name] as? JsonPrimitive
        primitive?.intOrNull ?: primitive?.contentOrNull?.trim()?.toIntOrNull()
    }

private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.firstNestedString(keys: Set<String>): String? {
    entries.forEach { (key, value) ->
        if (key in keys) {
            (value as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { return it }
        }
    }
    values.filterIsInstance<JsonObject>().forEach { child ->
        child.firstNestedString(keys)?.let { return it }
    }
    return null
}
