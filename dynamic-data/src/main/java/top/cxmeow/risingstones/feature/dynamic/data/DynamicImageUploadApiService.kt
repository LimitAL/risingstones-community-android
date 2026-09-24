package top.cxmeow.risingstones.feature.dynamic.data

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
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionScope
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicException
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadInput
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadService
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingImageUploadService
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicUploadedImage
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesCosSigning
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy

/** Uploads one explicitly selected image; the caller owns the surrounding operation scope. */
class DynamicImageUploadApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : DynamicImageUploadService, DynamicPublishingImageUploadService {
    override val canUploadImages: Boolean
        get() = RisingStonesCapability.DynamicImageUpload in sessionProvider.capabilities

    override val canAttemptImageUpload: Boolean
        get() {
            val provider = sessionProvider as? RisingStonesExplicitCapabilityProvider ?: return false
            return sessionProvider is RisingStonesCapabilityScopeProvider &&
                provider.canAttemptCapability(RisingStonesCapability.DynamicImageUpload) &&
                provider.canAttemptCapability(RisingStonesCapability.DynamicWrite)
        }

    override suspend fun uploadCommentImage(
        scope: DynamicActionScope,
        input: DynamicImageUploadInput,
    ): DynamicUploadedImage = uploadImage(scope, input, "default", DynamicImagePurpose.Comment)

    override suspend fun uploadPublishingImage(
        scope: DynamicActionScope,
        input: DynamicImageUploadInput,
    ): DynamicUploadedImage = uploadImage(scope, input, "dynamic", DynamicImagePurpose.Publishing)

    private suspend fun uploadImage(
        scope: DynamicActionScope,
        input: DynamicImageUploadInput,
        channel: String,
        purpose: DynamicImagePurpose,
    ): DynamicUploadedImage {
        val official = scope as? OfficialDynamicActionScope ?: throw DynamicException.AuthenticationRequired
        if (official.sessionProvider !== sessionProvider) throw DynamicException.AuthenticationRequired
        official.checkCurrent()
        val bytes = input.copyBytes()
        val context = RisingStonesRequestContext(TokenPath, RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.DynamicImageUpload)
        val attempt = official.delegate.beginCapabilityAttempt(context) ?: throw DynamicException.AuthenticationRequired
        try {
            val headers = mutableMapOf<String, String>()
            try { attempt.authorizer.authorize(context, RisingStonesHeaderSink(headers::set)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw DynamicException.AuthenticationRequired }
            official.checkCurrent()
            val response = try {
                client.execute(RisingStonesApiRequest(TokenPath,
                    query = listOf(RisingStonesApiQueryItem("channel", channel)), headers = headers))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                official.checkCurrent()
                if (error.httpAuthenticationFailure()) {
                    revalidateSession()
                    throw DynamicException.AuthenticationRequired
                }
                throw DynamicException.ImageUploadFailed
            }
            official.checkCurrent()
            if (response.statusCode in setOf(401, 403)) {
                revalidateSession()
                throw DynamicException.AuthenticationRequired
            }
            if (response.statusCode != 200) throw DynamicException.ImageUploadFailed
            val envelope = try { json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }
                catch (_: IllegalArgumentException) { null } ?: throw DynamicException.InvalidResponse
            val code = (envelope["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: throw DynamicException.InvalidResponse
            if (code == 10105) throw DynamicException.IdentityConflict
            if (code in setOf(401, 403, 10001, 10003, 10004, 10005, 10403)) {
                revalidateSession()
                throw DynamicException.AuthenticationRequired
            }
            if (!RisingStonesResponsePolicy.accepts(code)) throw DynamicException.Business(code)
            val data = envelope["data"] as? JsonObject ?: throw DynamicException.InvalidResponse
            val credentials = data["credentials"] as? JsonObject ?: throw DynamicException.InvalidResponse
            val secretId = credentials.secret("tmpSecretId")
            val secretKey = credentials.secret("tmpSecretKey")
            val sessionToken = credentials.secret("sessionToken")
            val start = (data["startTime"] as? JsonPrimitive)?.longOrNull ?: throw DynamicException.InvalidResponse
            val expiry = (data["expiredTime"] as? JsonPrimitive)?.longOrNull ?: throw DynamicException.InvalidResponse
            val keyDir = data.secret("keyDir")
            val now = nowEpochSeconds()
            if (start <= 0 || start > now || now >= expiry || start >= expiry || !validDirectory(keyDir)) {
                throw DynamicException.ImageUploadFailed
            }
            val uploadUrl = "https://ff14risingstones.gcloud.com.cn/$keyDir/${objectName(input.mimeType)}"
            val signature = try { RisingStonesCosSigning.putAuthorization(uploadUrl, bytes.size,
                secretId, secretKey, start, expiry) }
                catch (_: IllegalArgumentException) { throw DynamicException.ImageUploadFailed }
            official.checkCurrent()
            val guard = attempt as? RisingStonesCapabilityAttemptGuard ?: throw DynamicException.AuthenticationRequired
            if (!guard.isCurrent()) throw DynamicException.AuthenticationRequired
            val uploaded = try {
                // This request contains COS credentials only, never the official session headers.
                client.executeAbsolute(RisingStonesHttpRequest(uploadUrl, RisingStonesHttpMethod.Put,
                    headers = mapOf("Content-Type" to input.mimeType, "Content-Length" to bytes.size.toString(),
                        "Authorization" to signature, "x-cos-security-token" to sessionToken),
                    body = bytes, contentType = input.mimeType))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                official.checkCurrent()
                throw DynamicException.ImageUploadFailed
            }
            official.checkCurrent()
            // COS 401/403 describes the temporary upload credential, not the WebView session.
            if (uploaded.statusCode !in 200..299) throw DynamicException.ImageUploadFailed
            if (!attempt.complete()) throw DynamicException.AuthenticationRequired
            official.checkCurrent()
            return UploadedImage(uploadUrl, official, purpose)
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
            else -> throw DynamicException.ImageUploadFailed
        }
        val randomPart = ByteArray(16).also(Random::nextBytes).joinToString("") { "%02x".format(it) }
        return "${System.currentTimeMillis()}_$randomPart.$extension"
    }

    private class UploadedImage(
        override val url: String,
        override val actionScope: OfficialDynamicActionScope,
        override val purpose: DynamicImagePurpose,
    ) : BoundDynamicUploadedImage {
        override fun toString(): String = "DynamicUploadedImage"
    }

    private companion object {
        const val TokenPath = "api/common/getCOSTokenI"
        val Random = SecureRandom()
    }
}

private suspend fun OfficialDynamicActionScope.checkCurrent() {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw DynamicException.AuthenticationRequired
}

private fun JsonObject.secret(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: throw DynamicException.InvalidResponse

private fun validDirectory(value: String): Boolean = value.isNotBlank() && value.split('/').all { segment ->
    segment.isNotEmpty() && segment != "." && segment != ".." &&
        segment.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
}

private fun Throwable.httpAuthenticationFailure(): Boolean = generateSequence(this) { it.cause }
    .take(8).filterIsInstance<RisingStonesHttpException.ServerResponse>().any { it.statusCode in setOf(401, 403) }
