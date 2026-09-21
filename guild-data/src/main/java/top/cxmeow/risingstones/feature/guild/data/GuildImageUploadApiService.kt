package top.cxmeow.risingstones.feature.guild.data

import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScopeProvider
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService
import top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesCosSigning
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy

/** Uploads one explicitly selected image; the caller owns the surrounding operation scope. */
class GuildImageUploadApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : GuildImageUploadService {
    override val canUploadImages: Boolean
        get() = RisingStonesCapability.GuildImageUpload in sessionProvider.capabilities

    override val canAttemptImageUpload: Boolean
        get() {
            val provider = sessionProvider as? RisingStonesExplicitCapabilityProvider ?: return false
            return sessionProvider is RisingStonesCapabilityScopeProvider &&
                provider.canAttemptCapability(RisingStonesCapability.GuildImageUpload) &&
                provider.canAttemptCapability(RisingStonesCapability.GuildWrite)
        }

    override suspend fun uploadImage(
        scope: GuildActionScope,
        purpose: GuildImagePurpose,
        input: GuildImageUploadInput,
    ): GuildUploadedImage {
        val official = scope as? OfficialGuildActionScope ?: throw GuildException.AuthenticationRequired
        if (official.sessionProvider !== sessionProvider) throw GuildException.AuthenticationRequired
        official.checkCurrent()
        val bytes = input.copyBytes()
        val context = RisingStonesRequestContext(TokenPath, RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.GuildImageUpload)
        val attempt = official.delegate.beginCapabilityAttempt(context) ?: throw GuildException.AuthenticationRequired
        try {
            val headers = mutableMapOf<String, String>()
            try { attempt.authorizer.authorize(context, RisingStonesHeaderSink(headers::set)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw GuildException.AuthenticationRequired }
            official.checkCurrent()
            val response = try {
                client.execute(RisingStonesApiRequest(TokenPath,
                    query = listOf(RisingStonesApiQueryItem("channel", purpose.officialChannel)), headers = headers))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                official.checkCurrent()
                if (error.httpAuthenticationFailure()) {
                    revalidateSession()
                    throw GuildException.AuthenticationRequired
                }
                throw GuildException.ImageUploadFailed
            }
            official.checkCurrent()
            if (response.statusCode in setOf(401, 403)) {
                revalidateSession()
                throw GuildException.AuthenticationRequired
            }
            if (response.statusCode != 200) throw GuildException.ImageUploadFailed
            val envelope = try { json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }
                catch (_: IllegalArgumentException) { null } ?: throw GuildException.InvalidResponse
            val code = (envelope["code"] as? JsonPrimitive)?.intOrNull
            if (code == 10105) throw GuildException.IdentityConflict
            if (code in setOf(401, 403, 10001, 10003, 10004, 10005, 10403)) {
                revalidateSession()
                throw GuildException.AuthenticationRequired
            }
            if (!RisingStonesResponsePolicy.accepts(code)) throw GuildException.Business(code)
            val data = envelope["data"] as? JsonObject ?: throw GuildException.InvalidResponse
            val credentials = data["credentials"] as? JsonObject ?: throw GuildException.InvalidResponse
            val secretId = credentials.secret("tmpSecretId")
            val secretKey = credentials.secret("tmpSecretKey")
            val sessionToken = credentials.secret("sessionToken")
            val start = (data["startTime"] as? JsonPrimitive)?.longOrNull ?: throw GuildException.InvalidResponse
            val expiry = (data["expiredTime"] as? JsonPrimitive)?.longOrNull ?: throw GuildException.InvalidResponse
            val keyDir = data.secret("keyDir")
            val now = nowEpochSeconds()
            if (start <= 0 || start > now || now >= expiry || start >= expiry || !validDirectory(keyDir)) {
                throw GuildException.ImageUploadFailed
            }
            val uploadUrl = "https://ff14risingstones.gcloud.com.cn/$keyDir/${objectName(input.mimeType)}"
            val signature = try { RisingStonesCosSigning.putAuthorization(uploadUrl, bytes.size,
                secretId, secretKey, start, expiry) }
                catch (_: IllegalArgumentException) { throw GuildException.ImageUploadFailed }
            official.checkCurrent()
            val guard = attempt as? RisingStonesCapabilityAttemptGuard ?: throw GuildException.AuthenticationRequired
            if (!guard.isCurrent()) throw GuildException.AuthenticationRequired
            val uploaded = try {
                // This request contains COS credentials only, never the official session headers.
                client.executeAbsolute(RisingStonesHttpRequest(uploadUrl, RisingStonesHttpMethod.Put,
                    headers = mapOf("Content-Type" to input.mimeType, "Content-Length" to bytes.size.toString(),
                        "Authorization" to signature, "x-cos-security-token" to sessionToken),
                    body = bytes, contentType = input.mimeType))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                official.checkCurrent()
                throw GuildException.ImageUploadFailed
            }
            official.checkCurrent()
            // COS 401/403 describes the temporary upload credential, not the WebView session.
            if (uploaded.statusCode !in 200..299) throw GuildException.ImageUploadFailed
            if (!attempt.complete()) throw GuildException.AuthenticationRequired
            official.checkCurrent()
            return UploadedImage(uploadUrl, purpose, official)
        } finally {
            attempt.close()
        }
    }

    private suspend fun revalidateSession() {
        try { sessionProvider.refreshAuthorizer() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* The failed upload is never replayed. */ }
    }

    private fun objectName(mimeType: String): String {
        val extension = when (mimeType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> throw GuildException.ImageUploadFailed
        }
        val randomPart = ByteArray(16).also(Random::nextBytes).joinToString("") { "%02x".format(it) }
        return "${System.currentTimeMillis()}_$randomPart.$extension"
    }

    private class UploadedImage(
        override val url: String,
        override val purpose: GuildImagePurpose,
        override val actionScope: OfficialGuildActionScope,
    ) : BoundGuildUploadedImage {
        override fun toString(): String = "GuildUploadedImage(purpose=$purpose)"
    }

    private companion object {
        const val TokenPath = "api/common/getCOSTokenI"
        val Random = SecureRandom()
    }
}

private suspend fun OfficialGuildActionScope.checkCurrent() {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw GuildException.AuthenticationRequired
}

private fun JsonObject.secret(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: throw GuildException.InvalidResponse

private fun validDirectory(value: String): Boolean = value.isNotBlank() && value.split('/').all { segment ->
    segment.isNotEmpty() && segment != "." && segment != ".." &&
        segment.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
}

private fun Throwable.httpAuthenticationFailure(): Boolean = generateSequence(this) { it.cause }
    .take(8).filterIsInstance<RisingStonesHttpException.ServerResponse>().any { it.statusCode in setOf(401, 403) }
