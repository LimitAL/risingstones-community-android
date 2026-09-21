package top.cxmeow.risingstones.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext

class RisingStonesApiException(
    message: String,
    val code: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

data class RisingStonesSessionValidation(
    val displayName: String?,
    val code: Int,
    val capabilities: Set<RisingStonesCapability> = emptySet(),
)

fun interface RisingStonesSessionValidator {
    suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation
}

class RisingStonesApiClient(
    private val httpClient: OkHttpClient,
    baseUrl: String = OfficialRisingStonesEndpoints.ApiBaseUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RisingStonesSessionValidator {
    private val baseUrl: HttpUrl = baseUrl.ensureTrailingSlash().toHttpUrl()

    override suspend fun validateSession(
        authorizer: RisingStonesRequestAuthorizer,
    ): RisingStonesSessionValidation {
        val path = "api/home/GHome/isLogin"
        val requestBuilder = Request.Builder().url(baseUrl.resolve(path) ?: error("Invalid API path"))
        authorizer.authorize(
            context = RisingStonesRequestContext(
                path = path,
                requirement = RisingStonesAuthenticationRequirement.Required,
            ),
            sink = RisingStonesHeaderSink(requestBuilder::header),
        )
        val response = httpClient.newCall(requestBuilder.get().build()).executeAsync()
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw RisingStonesApiException(
                    message = "Rising Stones session validation failed with HTTP ${it.code}",
                    code = it.code,
                )
            }
            val envelope = runCatching {
                json.decodeFromString<SessionEnvelope>(body)
            }.getOrElse { error ->
                throw RisingStonesApiException(
                    message = "Rising Stones session response could not be decoded",
                    cause = error,
                )
            }
            if (!RisingStonesResponsePolicy.accepts(envelope.code)) {
                throw RisingStonesApiException(
                    message = envelope.message?.takeIf(String::isNotBlank)
                        ?: "Rising Stones session is not valid",
                    code = envelope.code,
                )
            }
            return RisingStonesSessionValidation(
                displayName = (envelope.data as? JsonObject)?.displayName(),
                code = envelope.code,
            )
        }
    }
}

object OfficialRisingStonesEndpoints {
    const val WebBaseUrl = "https://ff14risingstones.web.sdo.com/"
    const val LoginUrl = "${WebBaseUrl}mob/index.html#/index"
    const val ApiBaseUrl = "https://apiff14risingstones.web.sdo.com/"
}

@Serializable
private data class SessionEnvelope(
    val code: Int,
    @SerialName("msg") val message: String? = null,
    val data: JsonElement? = null,
)

private fun JsonObject.displayName(): String? {
    val keys = listOf("characterName", "character_name", "name", "nickName", "nickname")
    return keys.firstNotNullOfOrNull { key ->
        get(key)?.toString()?.trim('"')?.takeIf(String::isNotBlank)
    }
}

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
