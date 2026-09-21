package top.cxmeow.risingstones.feature.guild.data

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildActivitySummary
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildActionService
import top.cxmeow.risingstones.feature.guild.domain.GuildCommentActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildHousing
import top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildInfo
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.domain.GuildGuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildLabel
import top.cxmeow.risingstones.feature.guild.domain.GuildMember
import top.cxmeow.risingstones.feature.guild.domain.GuildMemberRegistration
import top.cxmeow.risingstones.feature.guild.domain.GuildMembers
import top.cxmeow.risingstones.feature.guild.domain.GuildPage
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoComment
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentDraft
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoDetail
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoSummary
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy

class GuildApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : GuildService, GuildActionService {
    private val commentOwnership = GuildCommentOwnershipRegistry()
    private val actions = GuildActionApiDelegate(client, sessionProvider, json, commentOwnership)

    override val canRead: Boolean
        get() = RisingStonesCapability.GuildRead in sessionProvider.capabilities

    override val canPerformAuthenticatedWrites: Boolean
        get() = actions.canPerformAuthenticatedWrites

    override val canAttemptAuthenticatedWrites: Boolean
        get() = actions.canAttemptAuthenticatedWrites

    override suspend fun beginActionScope(): GuildActionScope = actions.beginActionScope()

    override suspend fun labels(scope: GuildActionScope): List<GuildLabel> = actions.labels(scope)

    override suspend fun guildEligibility(
        scope: GuildActionScope,
        guildId: GuildId,
    ): GuildGuildActionEligibility = actions.guildEligibility(scope, guildId)

    override suspend fun photoEligibility(
        scope: GuildActionScope,
        photoId: Int,
    ): GuildPhotoActionEligibility = actions.photoEligibility(scope, photoId)

    override suspend fun commentEligibility(
        scope: GuildActionScope,
        commentId: Int,
    ): GuildCommentActionEligibility = actions.commentEligibility(scope, commentId)

    override suspend fun updateGuildInfo(
        scope: GuildActionScope,
        guildId: GuildId,
        update: GuildInfoUpdate,
    ) = actions.updateGuildInfo(scope, guildId, update)

    override suspend fun togglePhotoLike(
        scope: GuildActionScope,
        photoId: Int,
    ): GuildPhotoLikeResult = actions.togglePhotoLike(scope, photoId)

    override suspend fun commentPhoto(
        scope: GuildActionScope,
        draft: GuildPhotoCommentDraft,
    ) = actions.commentPhoto(scope, draft)

    override suspend fun deleteOwnComment(scope: GuildActionScope, commentId: Int) =
        actions.deleteOwnComment(scope, commentId)

    override suspend fun registerAlbumPhotos(
        scope: GuildActionScope,
        guildId: GuildId,
        images: List<GuildUploadedImage>,
    ) = actions.registerAlbumPhotos(scope, guildId, images)

    override suspend fun deletePhoto(scope: GuildActionScope, photoId: Int) =
        actions.deletePhoto(scope, photoId)

    override suspend fun ownGuild(): OwnGuild = parseOwnGuild(
        get(BasicInfoPath).requiredObject(),
    )

    override suspend fun info(guildId: GuildId): GuildInfo {
        val result = guildInfo(
            get(guildPath("getGuildInfo"), listOf(q("guild_id", guildId.value))).requiredObject(),
        )
        if (result.id != guildId) throw GuildException.InvalidResponse
        return result
    }

    override suspend fun members(guildId: GuildId): GuildMembers {
        val data = get(guildPath("getGuildMember"), listOf(q("guild_id", guildId.value))).requiredObject()
        return GuildMembers(
            registered = data.requiredArray("registered").map { member(it.requiredObject(), true) },
            unregistered = data.requiredArray("unRegister").map { member(it.requiredObject(), false) },
        )
    }

    override suspend fun activities(guildId: GuildId, page: Int): GuildPage<GuildActivitySummary> {
        require(page > 0)
        val data = get(
            guildPath("guildMemberDynamic"),
            listOf(q("guild_id", guildId.value), q("page", page), q("limit", ActivityPageSize)),
        ).requiredObject()
        val rows = data.requiredArray("rows")
        val items = rows.map { activity(it.requiredObject()) }
        val total = data.optionalNonNegativeInt("count")
        return GuildPage(
            items = items,
            page = page,
            hasMore = rows.isNotEmpty() && (
                total?.let { page.toLong() * ActivityPageSize < it.toLong() }
                    ?: (rows.size >= ActivityPageSize)
                ),
            totalCount = total,
        )
    }

    override suspend fun photos(guildId: GuildId, page: Int): GuildPage<GuildPhotoSummary> {
        require(page > 0)
        val data = get(
            guildPath("getGuildPhotos"),
            listOf(q("guild_id", guildId.value), q("page", page), q("limit", PhotoPageSize)),
        ).requiredObject()
        val rows = data.requiredArray("rows")
        return GuildPage(
            items = rows.map { photoSummary(it.requiredObject()) },
            page = page,
            // The official album continues after every non-empty page, including a short page.
            hasMore = rows.isNotEmpty(),
            totalCount = data.optionalNonNegativeInt("count"),
        )
    }

    override suspend fun photo(id: Int): GuildPhotoDetail {
        require(id > 0)
        val result = photoDetail(
            get(guildPath("getGuildPhotoDetail"), listOf(q("id", id))).requiredObject(),
        )
        if (result.id != id) throw GuildException.InvalidResponse
        return result
    }

    override suspend fun comments(
        photoId: Int,
        page: Int,
        pageTime: String?,
    ): GuildPage<GuildPhotoComment> {
        require(photoId > 0)
        require(page > 0)
        val query = buildList {
            add(q("photo_id", photoId))
            add(q("page", page))
            add(q("limit", CommentPageSize))
            if (page > 1 && !pageTime.isNullOrBlank()) add(q("pageTime", pageTime))
        }
        val data = get(guildPath("GuildPhotoCommentDetail"), query).requiredObject()
        val rows = data.requiredArray("rows")
        val items = rows.map { comment(it.requiredObject(), photoId, null) }
        items.forEach { commentOwnership.observe(it.id, it.authorUuid) }
        return GuildPage(
            items = items,
            page = page,
            // The website requests another page after any non-empty top-level comment page.
            hasMore = rows.isNotEmpty(),
            nextPageTime = data.optionalText("pageTime")?.takeIf(String::isNotBlank),
        )
    }

    override suspend fun replies(rootParentId: Int, page: Int): GuildPage<GuildPhotoComment> {
        require(rootParentId > 0)
        require(page > 0)
        val data = get(
            guildPath("guildPhotoSubCommentDetail"),
            listOf(
                q("root_parent", rootParentId),
                q("order", "earliest"),
                q("page", page),
                q("limit", CommentPageSize),
            ),
        ).requiredObject()
        val rows = data.requiredArray("rows")
        val items = rows.map { comment(it.requiredObject(), null, rootParentId) }
        items.forEach { commentOwnership.observe(it.id, it.authorUuid) }
        return GuildPage(
            items = items,
            page = page,
            hasMore = rows.size >= CommentPageSize,
        )
    }

    private suspend fun get(
        path: String,
        query: List<RisingStonesApiQueryItem> = emptyList(),
    ): JsonElement {
        requireReadAccess()
        return try {
            val authorizer = sessionProvider.currentAuthorizer()
                ?: throw GuildException.AuthenticationRequired
            requireReadAccess()
            val payload = try {
                readGuildPayload(client, json, authorizer, path, query)
            } catch (_: GuildException.AuthenticationRequired) {
                requireReadAccess()
                val refreshed = sessionProvider.refreshAuthorizer()
                    ?: throw GuildException.AuthenticationRequired
                requireReadAccess()
                readGuildPayload(client, json, refreshed, path, query)
            }
            requireReadAccess()
            payload
        } catch (error: CancellationException) {
            throw error
        } catch (error: GuildException) {
            throw error
        } catch (_: Exception) {
            throw GuildException.Network
        }
    }

    private suspend fun requireReadAccess() {
        currentCoroutineContext().ensureActive()
        if (!canRead) throw GuildException.Unavailable
    }
}

internal suspend fun readGuildPayload(
    client: RisingStonesPublicApiClient,
    json: Json,
    authorizer: RisingStonesRequestAuthorizer,
    path: String,
    query: List<RisingStonesApiQueryItem> = emptyList(),
): JsonElement {
    currentCoroutineContext().ensureActive()
    val headers = linkedMapOf<String, String>()
    authorizer.authorize(
        RisingStonesRequestContext(
            path = path,
            requirement = RisingStonesAuthenticationRequirement.Required,
            capability = RisingStonesCapability.GuildRead,
        ),
        RisingStonesHeaderSink { name, value -> headers[name] = value },
    )
    currentCoroutineContext().ensureActive()
    val response = try {
        client.execute(RisingStonesApiRequest(path = path, query = query, headers = headers))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val status = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<RisingStonesHttpException.ServerResponse>()
            .firstOrNull()
            ?.statusCode
        if (status == 401 || status == 403) throw GuildException.AuthenticationRequired
        throw GuildException.Network
    }
    currentCoroutineContext().ensureActive()
    if (response.statusCode == 401 || response.statusCode == 403) {
        throw GuildException.AuthenticationRequired
    }
    if (response.statusCode !in 200..299) throw GuildException.Network
    val envelope = try {
        json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw GuildException.InvalidResponse
    val code = envelope.optionalInt("code") ?: throw GuildException.InvalidResponse
    if (RisingStonesResponsePolicy.accepts(code)) {
        return envelope["data"] ?: throw GuildException.InvalidResponse
    }
    when (code) {
        10001, 10403, 10105 -> throw GuildException.AuthenticationRequired
        else -> throw GuildException.Business(code)
    }
}

internal fun parseOwnGuild(data: JsonObject): OwnGuild {
    val value = data.requiredText("gc_id")
    if (value.isEmpty() || value.any { it !in '0'..'9' }) throw GuildException.InvalidResponse
    return if (value.all { it == '0' }) OwnGuild.None else OwnGuild.Joined(guildId(value))
}

internal fun guildInfo(value: JsonObject): GuildInfo {
    val housingVisible = value.optionalFlag("house_public", false) ||
        value.optionalFlag("isGuildMember", false)
    return GuildInfo(
        id = guildId(value.requiredText("guild_id")),
        name = value.optionalText("guild_name").orEmpty(),
        tag = value.optionalText("guild_tag").orEmpty(),
        areaName = value.optionalText("area_name").orEmpty(),
        groupName = value.optionalText("group_name").orEmpty(),
        imageUrl = safeImage(value.optionalText("guild_pic")),
        descriptionHtml = value.optionalText("guild_describe").orEmpty(),
        createdAt = value.optionalText("create_time")?.takeIf(String::isNotBlank),
        // The official page uses parseInt and falls back to an unavailable rank.
        rank = value.optionalNonNegativeIntegerPrefix("guild_rank"),
        memberCount = value.optionalNonNegativeInt("member_num") ?: 0,
        activeMemberCount = value.optionalNonNegativeInt("active_member_num") ?: 0,
        grandCompanyName = value.optionalText("grand_parentname").orEmpty(),
        weekdayActiveTime = value.optionalText("active_time_weekday")?.takeIf(String::isNotBlank),
        weekendActiveTime = value.optionalText("active_time_weekend")?.takeIf(String::isNotBlank),
        labels = value.optionalStringArray("guild_label"),
        housing = if (housingVisible) {
            GuildHousing(
                GuildHousingVisibility.Visible,
                value.optionalText("house_info")?.takeIf(String::isNotBlank),
                value.optionalText("house_remain_day")?.takeIf(String::isNotBlank),
            )
        } else {
            GuildHousing(GuildHousingVisibility.Private, null, null)
        },
    )
}

private fun member(value: JsonObject, registered: Boolean): GuildMember = GuildMember(
    registration = if (registered) GuildMemberRegistration.Registered else GuildMemberRegistration.Unregistered,
    authorUuid = if (registered) value.optionalText("uuid")?.takeIf(String::isNotBlank) else null,
    characterName = value.optionalText("character_name").orEmpty(),
    areaName = value.optionalText("area_name").orEmpty(),
    groupName = value.optionalText("group_name").orEmpty(),
    avatarUrl = safeImage(value.optionalText("avatar")),
    profile = value.optionalText("profile").orEmpty(),
    relation = value.optionalInt("relation"),
    adminTag = value.optionalInt("admin_tag"),
)

private fun activity(value: JsonObject): GuildActivitySummary = GuildActivitySummary(
    id = value.requiredPositiveInt("id"),
    contentHtml = value.optionalText("mask_content").orEmpty(),
    imageUrls = safeImages(value.optionalText("pic_url")),
    createdAt = value.optionalText("created_at")?.takeIf(String::isNotBlank),
    authorUuid = value.optionalText("uuid")?.takeIf(String::isNotBlank),
    characterName = value.optionalText("character_name").orEmpty(),
    areaName = value.optionalText("area_name").orEmpty(),
    groupName = value.optionalText("group_name").orEmpty(),
    avatarUrl = safeImage(value.optionalText("avatar")),
)

private fun photoSummary(value: JsonObject): GuildPhotoSummary = GuildPhotoSummary(
    id = value.requiredPositiveInt("id"),
    photoUrl = value.requiredImage("photo_url"),
    characterName = value.optionalText("character_name").orEmpty(),
    avatarUrl = safeImage(value.optionalText("avatar")),
    commentCount = value.optionalNonNegativeInt("comment_count") ?: 0,
    likeCount = value.optionalNonNegativeInt("like_count") ?: 0,
    isLiked = value.optionalFlag("is_like", false),
)

private fun photoDetail(value: JsonObject): GuildPhotoDetail {
    val user = value["userInfo"] as? JsonObject
    return GuildPhotoDetail(
        id = value.requiredPositiveInt("id"),
        guildId = guildId(value.requiredText("guild_id")),
        photoUrl = value.requiredImage("photo_url"),
        authorUuid = value.optionalText("uuid")?.takeIf(String::isNotBlank),
        characterName = value.optionalText("character_name") ?: user?.optionalText("character_name").orEmpty(),
        areaName = value.optionalText("area_name") ?: user?.optionalText("area_name").orEmpty(),
        groupName = value.optionalText("group_name") ?: user?.optionalText("group_name").orEmpty(),
        avatarUrl = safeImage(value.optionalText("avatar") ?: user?.optionalText("avatar")),
        relayCount = value.optionalNonNegativeInt("relay_count") ?: 0,
        commentCount = value.optionalNonNegativeInt("comment_count") ?: 0,
        likeCount = value.optionalNonNegativeInt("like_count") ?: 0,
        isLiked = value.optionalFlag("is_like", false),
    )
}

private fun comment(
    value: JsonObject,
    requestedPhotoId: Int?,
    requestedRootParentId: Int?,
): GuildPhotoComment {
    val responsePhotoId = value.optionalPositiveInt("posts_id")
    if (requestedPhotoId != null && responsePhotoId != null && responsePhotoId != requestedPhotoId) {
        throw GuildException.InvalidResponse
    }
    val photoId = requestedPhotoId ?: responsePhotoId ?: throw GuildException.InvalidResponse
    val parentId = value.optionalNonNegativeInt("parent_id") ?: 0
    val rootParentId = value.optionalNonNegativeInt("root_parent") ?: 0
    if (requestedRootParentId != null && rootParentId != requestedRootParentId) {
        throw GuildException.InvalidResponse
    }
    return GuildPhotoComment(
        id = value.requiredPositiveInt("id"),
        photoId = photoId,
        parentId = parentId,
        rootParentId = rootParentId,
        authorUuid = value.optionalText("uuid")?.takeIf(String::isNotBlank),
        characterName = value.optionalText("character_name").orEmpty(),
        areaName = value.optionalText("area_name").orEmpty(),
        groupName = value.optionalText("group_name").orEmpty(),
        avatarUrl = safeImage(value.optionalText("avatar")),
        contentHtml = value.optionalText("mask_content").orEmpty(),
        pictureUrl = safeImage(value.optionalText("comment_pic")),
        createdAt = value.optionalText("created_at")?.takeIf(String::isNotBlank),
        replyToUuid = value.optionalText("to_uuid")?.takeIf(String::isNotBlank),
        replyToName = value.optionalText("to_cname")?.takeIf(String::isNotBlank),
        childCount = value.optionalNonNegativeInt("children_count") ?: 0,
    )
}

private fun guildId(value: String): GuildId = try {
    GuildId(value)
} catch (_: IllegalArgumentException) {
    throw GuildException.InvalidResponse
}

internal fun q(name: String, value: Any): RisingStonesApiQueryItem =
    RisingStonesApiQueryItem(name, value.toString())

private fun guildPath(endpoint: String) = "api/home/guild/$endpoint"

private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject
    ?: throw GuildException.InvalidResponse

private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray
    ?: throw GuildException.InvalidResponse

private fun JsonObject.requiredText(name: String): String = optionalText(name)
    ?: throw GuildException.InvalidResponse

private fun JsonObject.optionalText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optionalInt(name: String): Int? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val text = (element as? JsonPrimitive)?.contentOrNull ?: throw GuildException.InvalidResponse
    return text.toIntOrNull() ?: throw GuildException.InvalidResponse
}

private fun JsonObject.requiredPositiveInt(name: String): Int =
    optionalPositiveInt(name) ?: throw GuildException.InvalidResponse

private fun JsonObject.optionalPositiveInt(name: String): Int? {
    val value = optionalInt(name) ?: return null
    return value.takeIf { it > 0 } ?: throw GuildException.InvalidResponse
}

private fun JsonObject.optionalNonNegativeInt(name: String): Int? {
    val value = optionalInt(name) ?: return null
    return value.takeIf { it >= 0 } ?: throw GuildException.InvalidResponse
}

private fun JsonObject.optionalNonNegativeIntegerPrefix(name: String): Int? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val text = (element as? JsonPrimitive)?.contentOrNull ?: throw GuildException.InvalidResponse
    val unsigned = text.trimStart().removePrefix("+")
    if (unsigned.startsWith('-')) return null
    val digits = unsigned.takeWhile { it in '0'..'9' }
    return digits.takeIf(String::isNotEmpty)?.toIntOrNull()
}

private fun JsonObject.optionalFlag(name: String, default: Boolean): Boolean {
    val element = this[name] ?: return default
    if (element is JsonNull) return default
    val primitive = element as? JsonPrimitive ?: throw GuildException.InvalidResponse
    primitive.booleanOrNull?.let { return it }
    val value = optionalInt(name) ?: return default
    if (value != 0 && value != 1) throw GuildException.InvalidResponse
    return value == 1
}

private fun JsonObject.optionalStringArray(name: String): List<String> {
    val element = this[name] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    val rows = element as? JsonArray ?: throw GuildException.InvalidResponse
    return rows.map { row ->
        (row as? JsonPrimitive)?.contentOrNull ?: throw GuildException.InvalidResponse
    }.map(String::trim).filter(String::isNotEmpty)
}

private fun JsonObject.requiredImage(name: String): String =
    safeImage(optionalText(name)) ?: throw GuildException.InvalidResponse

private fun safeImages(value: String?): List<String> = value.orEmpty().split(',')
    .map(String::trim)
    .mapNotNull(::safeImage)
    .distinct()

private fun safeImage(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    return normalized.takeIf {
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase() in OfficialImageHosts
    }
}

internal const val BasicInfoPath = "api/home/userInfo/getUserBasicInfo"
private const val ActivityPageSize = 30
private const val PhotoPageSize = 20
private const val CommentPageSize = 20
private val OfficialImageHosts = setOf(
    "ff14risingstones.gcloud.com.cn",
    "ff14risingstones.web.sdo.com",
    "ff14-eo.web.sdo.com",
    "static.web.sdo.com",
)
