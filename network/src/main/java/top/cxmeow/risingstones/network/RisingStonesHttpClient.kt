package top.cxmeow.risingstones.network

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okhttp3.coroutines.executeAsync
import java.io.IOException

enum class RisingStonesHttpMethod {
    Get,
    Post,
    Put,
    Patch,
    Delete,
    Head,
}

data class RisingStonesHttpRequest(
    val url: String,
    val method: RisingStonesHttpMethod = RisingStonesHttpMethod.Get,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String = "application/json; charset=utf-8",
) {
    override fun toString(): String =
        "RisingStonesHttpRequest(" +
            "url=${url.redactedQuery()}, method=$method, headerNames=${headers.keys}, " +
            "bodySize=${body?.size ?: 0}, contentType=$contentType)"
}

data class RisingStonesHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    override fun toString(): String =
        "RisingStonesHttpResponse(" +
            "statusCode=$statusCode, headerNames=${headers.keys}, bodySize=${body.size})"
}

interface RisingStonesHttpClient {
    suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse
}

sealed class RisingStonesHttpException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidRequest(cause: Throwable) :
        RisingStonesHttpException("Invalid Rising Stones request", cause)

    class Transport(cause: Throwable) :
        RisingStonesHttpException("Rising Stones transport failed", cause)

    class ServerResponse(
        val statusCode: Int,
        val responseBody: ByteArray,
    ) : RisingStonesHttpException("Rising Stones returned HTTP $statusCode")
}

class OkHttpRisingStonesHttpClient(
    private val client: OkHttpClient,
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : RisingStonesHttpClient {
    private val writeClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE)
        .build()

    // Storage has its own short-lived authorization. A host application's cookie jar,
    // interceptors and default login headers must not accompany the signed upload.
    private val storageClient = writeClient.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .build()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        val storageRequest: Boolean
        val okHttpRequest = try {
            storageRequest = request.url.toHttpUrl().host == "ff14risingstones.gcloud.com.cn"
            if (storageRequest) request.copy(headers = request.headers.filterKeys {
                it.lowercase(java.util.Locale.ROOT) in setOf(
                    "authorization", "x-cos-security-token", "content-type", "content-length",
                )
            }).toOkHttpRequest(emptyMap()) else request.toOkHttpRequest(defaultHeaders)
        } catch (error: IllegalArgumentException) {
            throw RisingStonesHttpException.InvalidRequest(error)
        }
        val response = try {
            val transport = when {
                storageRequest -> storageClient
                request.method in setOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Head) -> client
                else -> writeClient
            }
            transport.newCall(okHttpRequest).executeAsync()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw RisingStonesHttpException.Transport(error)
        }
        response.use {
            val responseBody = it.body.bytes()
            if (!it.isSuccessful) {
                throw RisingStonesHttpException.ServerResponse(it.code, responseBody)
            }
            return RisingStonesHttpResponse(
                statusCode = it.code,
                headers = it.headers.toMultimap(),
                body = responseBody,
            )
        }
    }
}

private fun RisingStonesHttpRequest.toOkHttpRequest(
    defaultHeaders: Map<String, String>,
): Request {
    val builder = Request.Builder().url(url)
    // OkHttp replaces header names case-insensitively. Apply explicit session headers last so
    // a differently cased default cannot replace the User-Agent paired with the login Cookie.
    defaultHeaders.forEach(builder::header)
    headers.forEach(builder::header)
    // A one-shot body also prevents automatic HTTP 408/503 follow-up attempts.
    val requestBody = if (method in setOf(RisingStonesHttpMethod.Get, RisingStonesHttpMethod.Head)) null else
        object : RequestBody() {
            private val delegate = body?.toRequestBody(contentType.toMediaType())
                ?: ByteArray(0).toRequestBody()
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        }
    when (method) {
        RisingStonesHttpMethod.Get -> builder.get()
        RisingStonesHttpMethod.Post -> builder.post(requestBody ?: ByteArray(0).toRequestBody())
        RisingStonesHttpMethod.Put -> builder.put(requestBody ?: ByteArray(0).toRequestBody())
        RisingStonesHttpMethod.Patch -> builder.patch(requestBody ?: ByteArray(0).toRequestBody())
        RisingStonesHttpMethod.Delete ->
            if (requestBody == null) builder.delete() else builder.delete(requestBody)
        RisingStonesHttpMethod.Head -> builder.head()
    }
    return builder.build()
}

private fun String.redactedQuery(): String =
    if ('?' in this) "${substringBefore('?')}?<redacted>" else this
