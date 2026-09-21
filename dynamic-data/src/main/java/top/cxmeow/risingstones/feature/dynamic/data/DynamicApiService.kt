package top.cxmeow.risingstones.feature.dynamic.data

import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.network.*

class DynamicApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DynamicService {
    override val canRead: Boolean
        get() = RisingStonesCapability.DynamicRead in sessionProvider.capabilities

    override suspend fun fetchFeed(query: DynamicListQuery): DynamicPage<DynamicEntry> =
        get("getFollowDynamicList", query.parameters()).page(query, ::entry)

    override suspend fun fetchDetail(id: Int): DynamicEntry {
        require(id > 0)
        return entry(get("dynamicDetail", listOf(q("id", id))))
            ?: throw DynamicException.InvalidResponse
    }

    override suspend fun fetchComments(id: Int, query: DynamicListQuery): DynamicPage<DynamicComment> {
        require(id > 0)
        return get("dynamicCommentDetail", listOf(q("id", id)) + query.parameters()).page(query, ::comment)
    }

    override suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery): DynamicPage<DynamicComment> {
        require(rootParentId > 0)
        return get("dynamicSubCommentDetail", listOf(q("root_parent", rootParentId), q("order", "earliest")) +
            query.parameters()).page(query, ::comment)
    }

    private suspend fun get(endpoint: String, parameters: List<RisingStonesApiQueryItem>): JsonObject {
        if (!canRead) throw DynamicException.Unavailable
        try {
            val authorizer = sessionProvider.currentAuthorizer() ?: throw DynamicException.AuthenticationRequired
            return try {
                readDynamicPayload(client, json, authorizer, endpoint, parameters)
            } catch (_: DynamicException.AuthenticationRequired) {
                val refreshed = sessionProvider.refreshAuthorizer() ?: throw DynamicException.AuthenticationRequired
                if (!canRead) throw DynamicException.Unavailable
                readDynamicPayload(client, json, refreshed, endpoint, parameters)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DynamicException) {
            throw error
        } catch (_: Exception) {
            throw DynamicException.Network
        }
    }
}

internal suspend fun readDynamicPayload(
    client: RisingStonesPublicApiClient,
    json: Json,
    authorizer: RisingStonesRequestAuthorizer,
    endpoint: String,
    parameters: List<RisingStonesApiQueryItem>,
): JsonObject {
    val path = "api/home/dynamic/$endpoint"
    val headers = linkedMapOf<String, String>()
    authorizer.authorize(
        RisingStonesRequestContext(path, RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.DynamicRead),
        RisingStonesHeaderSink { name, value -> headers[name] = value },
    )
    val response = try {
        client.execute(RisingStonesApiRequest(path, query = parameters, headers = headers))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val httpFailure = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<RisingStonesHttpException.ServerResponse>().firstOrNull()
        if (httpFailure?.statusCode in listOf(401, 403)) throw DynamicException.AuthenticationRequired
        throw DynamicException.Network
    }
    if (response.statusCode in listOf(401, 403)) throw DynamicException.AuthenticationRequired
    if (response.statusCode !in 200..299) throw DynamicException.Network
    val envelope = try {
        json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) { null } ?: throw DynamicException.InvalidResponse
    val code = envelope.number("code")
    if (RisingStonesResponsePolicy.accepts(code)) {
        return envelope["data"] as? JsonObject ?: throw DynamicException.InvalidResponse
    }
    when (code) {
        10001, 10403, 10105 -> throw DynamicException.AuthenticationRequired
        else -> throw DynamicException.Business(code)
    }
}

private fun DynamicListQuery.parameters(): List<RisingStonesApiQueryItem> =
    listOf(q("page", page.coerceAtLeast(1)), q("limit", limit.coerceIn(1, 100))) +
        listOfNotNull(pageTime?.takeIf { page > 1 && it.isNotBlank() }?.let { q("pageTime", it) })

internal fun q(name: String, value: Any) = RisingStonesApiQueryItem(name, value.toString())

private fun <T> JsonObject.page(query: DynamicListQuery, map: (JsonObject) -> T?): DynamicPage<T> {
    val rows = this["rows"] as? JsonArray ?: throw DynamicException.InvalidResponse
    val mapped = rows.map { map(it as? JsonObject ?: throw DynamicException.InvalidResponse)
        ?: throw DynamicException.InvalidResponse }
    val page = query.page.coerceAtLeast(1)
    val limit = query.limit.coerceIn(1, 100)
    return DynamicPage(mapped, page, number("count")?.let { page * limit < it }
        ?: (rows.size >= limit), text("pageTime"))
}

private fun entry(value: JsonObject): DynamicEntry? {
    val origin = DynamicOrigin.entries.firstOrNull { it.wireValue == value.number("from") } ?: DynamicOrigin.Unknown
    val source = value["from_info"] as? JsonObject
    val reference = if (origin == DynamicOrigin.Original || source == null) null else DynamicReference(
        origin, value.text("from_id").orEmpty(), source.text("title").orEmpty(),
        source.text("desc") ?: source.text("content_pre").orEmpty(),
        imageUrls(source.text("main_image"), source.text("images"), source.text("cover_pic"), source.text("pic_url")),
    )
    return DynamicEntry(value.number("id")?.takeIf { it > 0 } ?: return null,
        value.author(), value.text("mask_content").orEmpty(), imageUrls(value.text("pic_url")),
        value.text("created_at").date(), value.number("comment_count") ?: 0, value.number("like_count") ?: 0,
        value.number("is_like") == 1, reference)
}

private fun comment(value: JsonObject): DynamicComment? = DynamicComment(
    value.number("id")?.takeIf { it > 0 } ?: return null, value.author(), value.text("mask_content").orEmpty(),
    imageUrls(value.text("comment_pic")), value.text("created_at").date(), value.text("to_cname"),
    value.number("like_count") ?: 0, value.number("children_count") ?: 0,
)

private fun JsonObject.author() = DynamicAuthor(text("uuid").orEmpty(), text("character_name").orEmpty(),
    text("area_name").orEmpty(), text("group_name").orEmpty(), imageUrls(text("avatar")).firstOrNull())

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Int? = text(key)?.toIntOrNull()

private fun String?.date(): Instant? = this?.let { text ->
    runCatching { Instant.parse(text) }.getOrNull() ?: runCatching {
        LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant()
    }.getOrNull()
}

private fun imageUrls(vararg values: String?): List<String> = values.filterNotNull().flatMap { it.split(',') }
    .map(String::trim).filter { url ->
        val uri = runCatching { URI(url) }.getOrNull()
        uri?.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase() in setOf("ff14risingstones.gcloud.com.cn", "ff14risingstones.web.sdo.com",
                "ff14-eo.web.sdo.com", "static.web.sdo.com")
    }.distinct()
