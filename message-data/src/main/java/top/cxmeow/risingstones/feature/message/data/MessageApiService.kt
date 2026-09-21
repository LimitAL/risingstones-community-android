package top.cxmeow.risingstones.feature.message.data

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.network.*

class MessageApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MessageAuthorService {
    override val canRead: Boolean
        get() = RisingStonesCapability.MessageRead in sessionProvider.capabilities

    override suspend fun fetchUnreadSummary(): MessageUnreadSummary =
        unreadSummary(get("getTip", emptyList()))

    override suspend fun readMessages(query: MessageQuery): MessagePage = readMessagesWithAuthors(query).page

    override suspend fun readMessagesWithAuthors(query: MessageQuery): MessageAuthorPage {
        val endpoint = when (query.category) {
            MessageCategory.System -> "getSysMsg"
            MessageCategory.Mentions -> "atMyMsg"
            MessageCategory.Comments -> "commentMsg"
            MessageCategory.Likes -> "likeMyMsg"
            MessageCategory.Recruitment -> "myRecruit"
            MessageCategory.Responses -> "myRecruitResponse"
        }
        val page = query.page.coerceAtLeast(1)
        val parameters = mutableListOf(q("page", page), q("limit", PageSize))
        when (query.category) {
            MessageCategory.Comments -> parameters += q("channel", if (query.commentChannel == MessageCommentChannel.Received) 1 else 2)
            MessageCategory.Recruitment, MessageCategory.Responses -> parameters += q("channel", query.recruitmentChannel.channel())
            else -> Unit
        }
        val payload = get(endpoint, parameters)
        val rows = when (query.category) {
            MessageCategory.System -> (payload as? JsonObject)?.get("data")
            MessageCategory.Mentions, MessageCategory.Recruitment, MessageCategory.Responses -> (payload as? JsonObject)?.get("rows")
            MessageCategory.Comments, MessageCategory.Likes -> payload
        } as? JsonArray ?: throw MessageException.InvalidResponse
        val count = (payload as? JsonObject)?.number("count")
        val authors = linkedMapOf<String, MessageAuthorTarget>()
        val seenKeys = hashSetOf<String>()
        val items = rows.map {
            val row = it as? JsonObject ?: throw MessageException.InvalidResponse
            val item = message(row, query)
            if (seenKeys.add(item.key)) messageAuthor(row, query)?.let { author -> authors[item.key] = author }
            item
        }
        // The official comments endpoint can return a sparse page after filtering its mixed sources.
        val hasMore = when {
            count != null -> page.toLong() * PageSize < count
            query.category == MessageCategory.Comments -> rows.isNotEmpty()
            else -> rows.size >= PageSize
        }
        return MessageAuthorPage(MessagePage(items, page, hasMore), authors)
    }

    private suspend fun get(endpoint: String, parameters: List<RisingStonesApiQueryItem>): JsonElement {
        return try {
            if (!canRead) throw MessageException.Unavailable
            val authorizer = sessionProvider.currentAuthorizer() ?: throw MessageException.AuthenticationRequired
            try {
                requestMessagePayload(client, json, authorizer, endpoint, parameters)
            } catch (_: MessageException.AuthenticationRequired) {
                val refreshed = sessionProvider.refreshAuthorizer() ?: throw MessageException.AuthenticationRequired
                if (!canRead) throw MessageException.Unavailable
                requestMessagePayload(client, json, refreshed, endpoint, parameters)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: MessageException) { throw error
        } catch (_: Exception) { throw MessageException.Network
        }
    }
}

internal suspend fun requestMessagePayload(
    client: RisingStonesPublicApiClient,
    json: Json,
    authorizer: RisingStonesRequestAuthorizer,
    endpoint: String,
    parameters: List<RisingStonesApiQueryItem> = emptyList(),
): JsonElement {
    val path = "api/home/sysMsg/$endpoint"
    val headers = linkedMapOf<String, String>()
    val response = try {
        authorizer.authorize(
            RisingStonesRequestContext(path, RisingStonesAuthenticationRequirement.Required, RisingStonesCapability.MessageRead),
            RisingStonesHeaderSink { name, value -> headers[name] = value },
        )
        client.execute(RisingStonesApiRequest(path, query = parameters, headers = headers))
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        val status = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<RisingStonesHttpException.ServerResponse>().firstOrNull()?.statusCode
        if (status in listOf(401, 403)) throw MessageException.AuthenticationRequired
        throw MessageException.Network
    }
    if (response.statusCode in listOf(401, 403)) throw MessageException.AuthenticationRequired
    if (response.statusCode !in 200..299) throw MessageException.Network
    val envelope = try { json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) { null } ?: throw MessageException.InvalidResponse
    val code = envelope.number("code")
    if (RisingStonesResponsePolicy.accepts(code)) {
        return envelope["data"]?.takeUnless { it is JsonNull } ?: throw MessageException.InvalidResponse
    }
    when (code) {
        10001, 10403, 10105 -> throw MessageException.AuthenticationRequired
        null -> throw MessageException.InvalidResponse
        else -> throw MessageException.Business(code)
    }
}

internal fun unreadSummary(payload: JsonElement): MessageUnreadSummary {
    val data = payload as? JsonObject ?: throw MessageException.InvalidResponse
    fun required(key: String) = data.number(key)?.coerceAtLeast(0) ?: throw MessageException.InvalidResponse
    return MessageUnreadSummary(required("sysNum"), required("atMsgNum"), required("commentMsgNum"),
        required("beLikedMsgNum"), required("recruitTip"), mapOf(
            MessageRecruitmentChannel.Beginner to required("recruitNeTip"),
            MessageRecruitmentChannel.Duty to required("recruitFbTip"),
            MessageRecruitmentChannel.Guild to required("recruitGuildTip"),
            MessageRecruitmentChannel.Other to required("recruitOtherTip"),
        ))
}

private fun message(row: JsonObject, query: MessageQuery): CommunityMessage {
    val keyFields = listOf("msg_id", "id", "recruit_response_id", "from", "from_id", "from_root_id",
        "comment_from", "like_from", "posts_comment_id", "dynamic_comment_id", "rp_comment_id",
        "posts_id", "dynamic_id", "glamour_id", "rp_id", "guild_photo_id", "uuid", "comment_uuid",
        "like_uuid", "favorite_uuid", "created_at", "updated_at")
    val identity = keyFields.mapNotNull { key -> row.text(key)?.let { "$key=$it" } }.joinToString("|")
        .ifBlank { row.toString() }
    val key = MessageDigest.getInstance("SHA-256").digest((query.category.name + identity).toByteArray())
        .joinToString("") { "%02x".format(it) }
    val title = row.text("title", "posts_title", "fb_name", "guild_name").orEmpty()
    val content = if (query.category == MessageCategory.System || query.category == MessageCategory.Mentions) {
        row.text("content")
    } else row.text("mask_content", "content", "detail_mask")
    return CommunityMessage(key, query.category, row.text("character_name").orEmpty(),
        listOfNotNull(row.text("area_name"), row.text("group_name")).filter(String::isNotBlank).joinToString(" / "),
        title, content.orEmpty(), row.text("parent_mask_content", "dynamic_content", "content_supp", "content_pre").orEmpty(),
        listOfNotNull(row.text("pic_url"), row.text("comment_pic"), row.text("cover_pic"), row.text("main_image"))
            .flatMap { it.split(',') }.map(String::trim).filter(::reviewedUrl).distinct(),
        row.text("updated_at", "created_at").date(), target(row, query),
        row.text("link")?.takeIf(::reviewedUrl), row.text("contact_info_mask")?.takeIf(String::isNotBlank))
}

private fun target(row: JsonObject, query: MessageQuery): MessageTarget? {
    fun link(kind: MessageTargetKind, key: String, channel: MessageRecruitmentChannel? = null) =
        row.number(key)?.takeIf { it > 0 }?.let { MessageTarget(kind, it, channel) }
    fun post(key: String) = when (row.number("posts_type")) {
        1 -> link(MessageTargetKind.Post, key)
        2 -> link(MessageTargetKind.Guide, key)
        else -> null
    }
    return when (query.category) {
        MessageCategory.System -> null
        MessageCategory.Mentions -> when (row.number("from")) {
            1 -> link(MessageTargetKind.Dynamic, "from_id")
            2 -> link(MessageTargetKind.Dynamic, "from_root_id")
            3 -> post("from_id")
            4 -> post("from_root_id")
            6 -> link(MessageTargetKind.RolePlayRecruitment, "from_root_id")
            else -> null
        }
        MessageCategory.Comments -> when (row.number("comment_from")) {
            1 -> link(MessageTargetKind.Dynamic, "dynamic_id")
            2 -> link(MessageTargetKind.Post, "posts_id")
            3 -> link(MessageTargetKind.Guide, "posts_id")
            4 -> link(MessageTargetKind.GuildPhoto, "guild_photo_id")
            5 -> link(MessageTargetKind.RolePlayRecruitment, "rp_id")
            else -> null
        }
        MessageCategory.Likes -> when (row.number("like_from")) {
            1 -> link(MessageTargetKind.Dynamic, "dynamic_id")
            2, 4 -> link(MessageTargetKind.Post, "posts_id")
            3, 5 -> link(MessageTargetKind.Guide, "posts_id")
            6 -> link(MessageTargetKind.GuildPhoto, "guild_photo_id")
            7 -> link(MessageTargetKind.RolePlayRecruitment, "rp_id")
            8, 9 -> link(MessageTargetKind.Glamour, "glamour_id")
            else -> null
        }
        MessageCategory.Recruitment, MessageCategory.Responses -> {
            val key = when (query.recruitmentChannel) {
                MessageRecruitmentChannel.Beginner -> "recruit_ne_id"
                MessageRecruitmentChannel.Duty -> "recruit_fb_id"
                MessageRecruitmentChannel.Guild -> "recruit_guild_id"
                MessageRecruitmentChannel.Other -> "recruit_other_id"
            }
            link(MessageTargetKind.Recruitment, key, query.recruitmentChannel)
        }
    }
}

private fun MessageRecruitmentChannel.channel(): Int = when (this) {
    MessageRecruitmentChannel.Beginner -> 1
    MessageRecruitmentChannel.Duty -> 2
    MessageRecruitmentChannel.Guild -> 3
    MessageRecruitmentChannel.Other -> 4
}
private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.number(key: String): Int? = text(key)?.toIntOrNull()
private fun q(name: String, value: Any) = RisingStonesApiQueryItem(name, value.toString())
private fun String?.date(): Instant? = this?.let {
    runCatching { Instant.parse(it) }.getOrNull() ?: runCatching {
        LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
    }.getOrNull()
}
private fun reviewedUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        uri.host?.lowercase() in setOf("ff14risingstones.web.sdo.com", "ff14risingstones.gcloud.com.cn", "ff14-eo.web.sdo.com", "static.web.sdo.com")
}
private const val PageSize = 10

private fun messageAuthor(row: JsonObject, query: MessageQuery): MessageAuthorTarget? {
    val field = when (query.category) {
        MessageCategory.System -> return null
        MessageCategory.Mentions, MessageCategory.Recruitment -> "uuid"
        MessageCategory.Comments -> {
            if (query.commentChannel == MessageCommentChannel.Sent) return MessageAuthorTarget.Self
            "comment_uuid"
        }
        MessageCategory.Likes -> if (row.number("like_from") == 9) "favorite_uuid" else "like_uuid"
        MessageCategory.Responses -> return MessageAuthorTarget.Self
    }
    val uuid = (row[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return MessageAuthorTarget.Community(uuid)
}
