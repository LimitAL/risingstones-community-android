package top.cxmeow.risingstones.network

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl

data class RisingStonesApiQueryItem(
    val name: String,
    val value: String?,
)

data class RisingStonesApiRequest(
    val path: String,
    val method: RisingStonesHttpMethod = RisingStonesHttpMethod.Get,
    val query: List<RisingStonesApiQueryItem> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String = "application/json; charset=utf-8",
) {
    override fun toString(): String =
        "RisingStonesApiRequest(" +
            "path=$path, method=$method, queryNames=${query.map(RisingStonesApiQueryItem::name)}, " +
            "headerNames=${headers.keys}, bodySize=${body?.size ?: 0}, contentType=$contentType)"
}

class RisingStonesEndpointException(cause: Throwable?) :
    Exception("No configured Rising Stones endpoint is available", cause)

class RisingStonesPublicApiClient(
    private val transport: RisingStonesHttpClient,
    baseUrls: List<String> = listOf(OfficialRisingStonesEndpoints.ApiBaseUrl),
) {
    private val baseUrls = baseUrls.map(String::trim).filter(String::isNotEmpty)

    init {
        require(this.baseUrls.isNotEmpty()) { "At least one Rising Stones base URL is required" }
    }

    suspend fun execute(request: RisingStonesApiRequest): RisingStonesHttpResponse {
        var lastError: Throwable? = null
        for (baseUrl in baseUrls) {
            try {
                return transport.execute(
                    RisingStonesHttpRequest(
                        url = buildUrl(baseUrl, request),
                        method = request.method,
                        headers = request.headers,
                        body = request.body,
                        contentType = request.contentType,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw RisingStonesEndpointException(lastError)
    }

    suspend fun executeAbsolute(request: RisingStonesHttpRequest): RisingStonesHttpResponse =
        transport.execute(request)
}

private fun buildUrl(baseUrl: String, request: RisingStonesApiRequest): String {
    val builder = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
    request.path.trimStart('/').takeIf(String::isNotEmpty)?.let(builder::addPathSegments)
    request.query.forEach { builder.addQueryParameter(it.name, it.value) }
    return builder.build().toString()
}
