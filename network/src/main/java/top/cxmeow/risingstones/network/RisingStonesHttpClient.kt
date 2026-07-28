package top.cxmeow.risingstones.network

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        val okHttpRequest = try {
            request.toOkHttpRequest(defaultHeaders)
        } catch (error: IllegalArgumentException) {
            throw RisingStonesHttpException.InvalidRequest(error)
        }
        val response = try {
            client.newCall(okHttpRequest).executeAsync()
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
    buildMap {
        putAll(defaultHeaders)
        putAll(headers)
    }.forEach(builder::header)
    val requestBody = body?.toRequestBody(contentType.toMediaType())
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
