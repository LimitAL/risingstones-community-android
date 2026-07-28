package top.cxmeow.risingstones.feature.glamour.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesIdentityConflictResolver
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessory
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessorySearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDye
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipment
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipmentSearchResult
import top.cxmeow.risingstones.feature.glamour.domain.GlamourException
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourGlassesSearchGroup
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourProfileStatistics
import top.cxmeow.risingstones.feature.glamour.domain.GlamourRace
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class GlamourApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider?,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val temporarySessionId: String = UUID.randomUUID().toString(),
) : GlamourService {
    override val hasCommunityIdentity: Boolean
        get() = sessionProvider?.capabilities?.contains(
            RisingStonesCapability.GlamourAuthenticated,
        ) == true

    override suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage {
        val page = request.page.coerceAtLeast(1)
        val limit = request.limit.coerceAtLeast(1)
        val query = mutableListOf(
            q("page", page), q("limit", limit), q("tempsuid", temporarySessionId),
        )
        request.filter.order.wireValue?.let { query += q("order", it) }
        request.filter.raceId?.let { query += q("race_id", it) }
        request.filter.genderId?.let { query += q("gender_id", it) }
        request.filter.createTime?.takeIf(String::isNotBlank)?.let { query += q("createTime", it) }
        request.authorId?.takeIf(String::isNotBlank)?.let { query += q("uuid", it) }
        val search = request.search
        val isGlobalSearch = request.source == GlamourListSource.Community && search != null
        val path = when (request.source) {
            GlamourListSource.Favorites -> {
                query += q("favorite_id", requireNotNull(request.favoriteFolderId))
                search?.let { query += it.scopedQueryItems() }
                "api/home/glamour/myFavoriteItemsList"
            }
            GlamourListSource.Profile -> {
                if (request.filter.order.wireValue == null) query += q("order", "latest")
                if (search == null) {
                    query += q("title", "")
                } else {
                    query += search.scopedQueryItems(includeEmptyTitle = true)
                }
                "api/home/glamour/myGlamoursList"
            }
            GlamourListSource.Community -> if (search != null) {
                query += q("type", 7)
                query += q("keywords", search.keywords)
                if (search.searchByEquipment) query += q("searchByEquipment", 1)
                if (search.searchByGlasses) query += q("searchByGlasses", 1)
                if (search.searchByOrnament) query += q("searchByOrnament", 1)
                "api/common/search"
            } else {
                "api/home/glamour/glamoursList"
            }
        }
        val response = perform { headers ->
            RisingStonesApiRequest(path, query = query, headers = headers)
        }
        val payload = response.objectValue("data") ?: throw GlamourException.MissingPayload
        val items = payload.arrayValue("rows").mapNotNull(::listing)
        val reported = payload.intValue("count")
        val hasNext = if (isGlobalSearch) {
            reported?.let { page * limit < it } ?: (items.size >= limit)
        } else {
            maxOf(reported ?: 0, items.size) >= limit
        }
        return GlamourListPage(items, page, hasNext)
    }

    private fun GlamourSearchSelection.scopedQueryItems(
        includeEmptyTitle: Boolean = false,
    ): List<RisingStonesApiQueryItem> = when {
        searchByEquipment -> listOf(q("equip_id", keywords)) +
            if (includeEmptyTitle) listOf(q("title", "")) else emptyList()
        searchByGlasses -> listOf(q("glasses_id", keywords)) +
            if (includeEmptyTitle) listOf(q("title", "")) else emptyList()
        searchByOrnament -> listOf(q("ornament_id", keywords)) +
            if (includeEmptyTitle) listOf(q("title", "")) else emptyList()
        else -> listOf(q("title", keywords))
    }

    override suspend fun fetchFavoriteFolders(authorId: String?): List<GlamourFavoriteFolder> {
        val folders = mutableListOf<GlamourFavoriteFolder>()
        var page = 1
        val limit = 10
        var total: Int? = null
        var received: Int
        do {
            val query = mutableListOf(q("page", page), q("limit", limit), q("tempsuid", temporarySessionId))
            authorId?.takeIf(String::isNotBlank)?.let { query += q("uuid", it) }
            val response = perform { headers ->
                RisingStonesApiRequest(
                    "api/home/glamour/myFavoritesList",
                    query = query,
                    headers = headers,
                )
            }
            val payload = response.objectValue("data")
            val next = payload?.arrayValue("rows").orEmpty().mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                val id = item.intValue("id") ?: return@mapNotNull null
                GlamourFavoriteFolder(
                    id,
                    item.stringValue("name").orEmpty(),
                    item.intValue("is_default", "isDefault") == 1,
                    item.intValue("is_public", "isPublic") == 1,
                    item.intValue("item_count", "itemCount") ?: 0,
                )
            }
            folders += next
            received = next.size
            total = payload?.intValue("count")
            page += 1
        } while (folders.size < (total ?: Int.MAX_VALUE) && received == limit)
        return folders
    }

    override suspend fun createFavoriteFolder(name: String, isPublic: Boolean) {
        form(
            "api/home/glamour/createFavorites",
            listOf("name" to name, "is_public" to if (isPublic) "1" else "0"),
        )
    }

    override suspend fun deleteFavoriteFolder(id: Int) {
        perform { headers ->
            RisingStonesApiRequest(
                "api/home/glamour/deleteFavorites",
                method = RisingStonesHttpMethod.Delete,
                query = listOf(q("tempsuid", temporarySessionId)),
                headers = headers,
                body = "{\"id\":\"$id\"}".encodeToByteArray(),
                contentType = "application/json; charset=utf-8",
            )
        }
    }

    override suspend fun fetchProfileStatistics(authorId: String?): GlamourProfileStatistics {
        val query = mutableListOf(q("tempsuid", temporarySessionId))
        authorId?.takeIf(String::isNotBlank)?.let { query += q("uuid", it) }
        val data = perform { headers ->
            RisingStonesApiRequest(
                "api/home/glamour/myGlamoursNum",
                query = query,
                headers = headers,
            )
        }.objectValue("data") ?: throw GlamourException.MissingPayload
        return GlamourProfileStatistics(
            data.intValue("create_glamour_num", "createGlamourNum") ?: 0,
            data.intValue("be_liked_num", "beLikedNum") ?: 0,
            data.intValue("be_favorited_num", "beFavoritedNum") ?: 0,
        )
    }

    override suspend fun fetchRaces(): List<GlamourRace> = perform { headers ->
        RisingStonesApiRequest(
            "api/home/gameData/getAllRace",
            query = listOf(q("tempsuid", temporarySessionId)),
            headers = headers,
        )
    }.arrayValue("data").mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        GlamourRace(item.intValue("id") ?: return@mapNotNull null, item.stringValue("name") ?: return@mapNotNull null)
    }

    override suspend fun searchEquipment(name: String, page: Int): List<GlamourEquipmentSearchResult> {
        val data = perform { headers ->
            RisingStonesApiRequest(
                "api/home/gameData/searchEquip",
                query = listOf(q("page", page.coerceAtLeast(1)), q("limit", 20), q("name", name), q("tempsuid", temporarySessionId)),
                headers = headers,
            )
        }.objectValue("data")
        return data?.arrayValue("rows").orEmpty().mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            GlamourEquipmentSearchResult(
                item.intValue("id") ?: return@mapNotNull null,
                item.stringValue("name") ?: return@mapNotNull null,
                item.stringValue("desc").orEmpty(),
                item.stringValue("icon_id", "iconId"),
                item.arrayValue("class_jobs", "classJobs").mapNotNull {
                    (it as? JsonObject)?.stringValue("name")
                },
            )
        }
    }

    override suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup> =
        perform { headers ->
            RisingStonesApiRequest(
                "api/home/gameData/getGlassesList",
                query = listOf(q("name", name), q("tempsuid", temporarySessionId)),
                headers = headers,
            )
        }.arrayValue("data").mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            GlamourGlassesSearchGroup(
                item.intValue("style_id", "styleId") ?: return@mapNotNull null,
                item.stringValue("style_name", "styleName") ?: return@mapNotNull null,
                item.arrayValue("list").mapNotNull(::searchAccessory),
            )
        }

    override suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult> =
        perform { headers ->
            RisingStonesApiRequest(
                "api/home/gameData/getOrnamentList",
                query = listOf(q("name", name), q("tempsuid", temporarySessionId)),
                headers = headers,
            )
        }.objectValue("data")?.arrayValue("rows").orEmpty().mapNotNull(::searchAccessory)

    override suspend fun fetchDetail(id: Int): GlamourDetail {
        val data = perform { headers ->
            RisingStonesApiRequest(
                "api/home/glamour/glamourDetail",
                query = listOf(q("id", id), q("tempsuid", temporarySessionId)),
                headers = headers,
            )
        }.objectValue("data") ?: throw GlamourException.MissingPayload
        val detailId = data.intValue("id") ?: throw GlamourException.MissingPayload
        val user = data.objectValue("user_info", "userInfo")
        val ornament = data.objectValue("ort_info", "ortInfo")
        return GlamourDetail(
            detailId,
            data.stringValue("title").orEmpty(),
            data.stringValue("desc").orEmpty(),
            imageUrls(data),
            GlamourAuthor(
                data.stringValue("uuid") ?: user?.stringValue("uuid"),
                data.stringValue("character_name", "characterName")
                    ?: user?.stringValue("character_name", "characterName") ?: "—",
                data.stringValue("area_name", "areaName")
                    ?: user?.stringValue("area_name", "areaName").orEmpty(),
                data.stringValue("group_name", "groupName")
                    ?: user?.stringValue("group_name", "groupName").orEmpty(),
                user?.stringValue("avatar"),
            ),
            data.intValue("likes") ?: 0,
            data.intValue("favorites") ?: 0,
            (data.intValue("is_like", "isLike") ?: 0) != 0,
            (data.intValue("is_favorite", "isFavorite") ?: 0) != 0,
            parseDate(data.stringValue("created_at", "createdAt")),
            data.arrayValue("race_ids", "raceIds").mapNotNull { (it as? JsonObject)?.stringValue("name") },
            data.arrayValue("equipments").mapNotNull(::equipment),
            ornament?.accessory("glasses") ,
            ornament?.accessory("ornament"),
        )
    }

    override suspend fun favorite(id: Int) {
        val folder = fetchFavoriteFolders().firstOrNull(GlamourFavoriteFolder::isDefault)
            ?: throw GlamourException.MissingDefaultFavoriteFolder
        form("api/home/glamour/favorite", listOf("id" to "$id", "favorite_id" to "${folder.id}"))
    }

    override suspend fun cancelFavorite(id: Int) {
        form("api/home/glamour/cancelFavorite", listOf("id" to "$id"))
    }

    override suspend fun toggleLike(id: Int): Boolean =
        form("api/home/glamour/like", listOf("id" to "$id")).intValue("data") == 1

    private suspend fun form(path: String, fields: List<Pair<String, String>>): JsonObject =
        perform { headers ->
            val all = fields + ("tempsuid" to temporarySessionId)
            RisingStonesApiRequest(
                path,
                method = RisingStonesHttpMethod.Post,
                query = listOf(q("tempsuid", temporarySessionId)),
                headers = headers,
                body = all.joinToString("&") { (key, value) ->
                    "${key.formEncoded()}=${value.formEncoded()}"
                }.encodeToByteArray(),
                contentType = "application/x-www-form-urlencoded; charset=utf-8",
            )
        }

    private suspend fun perform(
        request: (Map<String, String>) -> RisingStonesApiRequest,
    ): JsonObject {
        if (!hasCommunityIdentity) throw GlamourException.AuthenticationRequired
        val initialAuthorizer = sessionProvider?.currentAuthorizer()
            ?: throw GlamourException.AuthenticationRequired
        suspend fun execute(authorizer: RisingStonesRequestAuthorizer): JsonObject {
            val path = request(emptyMap()).path
            val headers = authorizer.headers(path)
            return json.parseToJsonElement(
                client.execute(request(headers)).body.decodeToString(),
            ).jsonObject
        }
        var response = try {
            execute(initialAuthorizer)
        } catch (error: Throwable) {
            when {
                error.isHttpIdentityConflict() -> execute(
                    (sessionProvider as? RisingStonesIdentityConflictResolver)
                        ?.awaitIdentityConflictResolution() ?: throw error,
                )
                error.isHttpAuthenticationFailure() -> execute(
                    sessionProvider.refreshAuthorizer() ?: throw error,
                )
                else -> throw error
            }
        }
        if (response.intValue("code") == 10105) {
            (sessionProvider as? RisingStonesIdentityConflictResolver)
                ?.awaitIdentityConflictResolution()
                ?.let { response = execute(it) }
        } else if (response.isAuthenticationFailure()) {
            sessionProvider.refreshAuthorizer()?.let { response = execute(it) }
        }
        val code = response.intValue("code") ?: 0
        if (code != 10000) throw GlamourException.Business(code, response.stringValue("msg", "message"))
        return response
    }

    private fun listing(value: JsonElement): GlamourListingSummary? {
        val item = value as? JsonObject ?: return null
        val id = item.intValue("id", "glamour_id", "glamourId") ?: return null
        return GlamourListingSummary(
            id,
            item.stringValue("title").orEmpty(),
            item.stringValue("desc").orEmpty(),
            imageUrls(item),
            GlamourAuthor(
                item.stringValue("uuid"),
                item.stringValue("character_name", "characterName") ?: "—",
                item.stringValue("area_name", "areaName").orEmpty(),
                item.stringValue("group_name", "groupName").orEmpty(),
                item.stringValue("avatar"),
            ),
            item.intValue("likes") ?: 0,
            item.intValue("favorites") ?: 0,
            (item.intValue("is_like", "isLike") ?: 0) != 0,
            (item.intValue("is_favorite", "isFavorite") ?: 0) != 0,
            parseDate(item.stringValue("glamour_created_at", "glamourCreatedAt", "created_at", "createdAt")),
            item.intArray("job_ids", "jobIds"),
            item.intArray("race_ids", "raceIds"),
            item.intArray("gender_ids", "genderIds"),
        )
    }

    private fun equipment(value: JsonElement): GlamourEquipment? {
        val item = value as? JsonObject ?: return null
        return GlamourEquipment(
            item.stringValue("slot") ?: return null,
            item.intValue("equipment_id", "equipmentId"),
            item.stringValue("name"),
            item.stringValue("icon_id", "iconId"),
            item.intArray("dye_ids", "dyeIds"),
            item.arrayValue("dyes").mapNotNull { dyeValue ->
                val dye = dyeValue as? JsonObject ?: return@mapNotNull null
                GlamourDye(
                    dye.intValue("id") ?: return@mapNotNull null,
                    dye.stringValue("name") ?: return@mapNotNull null,
                    dye.stringValue("color"),
                )
            },
        )
    }

    private fun searchAccessory(value: JsonElement): GlamourAccessorySearchResult? {
        val item = value as? JsonObject ?: return null
        return GlamourAccessorySearchResult(
            item.intValue("id") ?: return null,
            item.stringValue("name") ?: return null,
            item.stringValue("desc").orEmpty(),
            item.stringValue("icon"),
        )
    }

    private fun JsonObject.accessory(prefix: String): GlamourAccessory? {
        val id = intValue("${prefix}_id", "${prefix}Id") ?: return null
        val name = stringValue("${prefix}_name", "${prefix}Name")?.takeIf(String::isNotBlank)
            ?: return null
        return GlamourAccessory(id, name, stringValue("${prefix}_icon", "${prefix}Icon"))
    }

    private fun imageUrls(item: JsonObject): List<String> {
        val candidates = listOf(item.stringValue("main_image", "mainImage")) +
            item.stringValue("images").orEmpty().split(',')
        return candidates.mapNotNull { it?.trim()?.takeIf { value -> value.startsWith("http") } }.distinct()
    }

    private fun parseDate(value: String?): Instant? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        runCatching { return Instant.parse(text) }
        runCatching { return OffsetDateTime.parse(text).toInstant() }
        for (formatter in DATE_FORMATTERS) {
            runCatching { return LocalDateTime.parse(text, formatter).atZone(ZoneId.of("Asia/Shanghai")).toInstant() }
        }
        return null
    }

    private companion object {
        val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        )
    }
}

private suspend fun RisingStonesRequestAuthorizer.headers(path: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    authorize(
        RisingStonesRequestContext(
            path = path,
            requirement = RisingStonesAuthenticationRequirement.Required,
            capability = RisingStonesCapability.GlamourAuthenticated,
        ),
        RisingStonesHeaderSink { name, value -> headers[name] = value },
    )
    return headers
}

private fun q(name: String, value: Any?) =
    RisingStonesApiQueryItem(name, value?.toString().orEmpty())

private fun JsonObject.element(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { this[it]?.takeUnless { value -> value is JsonNull } }

private fun JsonObject.stringValue(vararg names: String): String? =
    (element(*names) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intValue(vararg names: String): Int? {
    val primitive = element(*names) as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        ?: primitive.doubleOrNull?.toInt()
}

private fun JsonObject.objectValue(vararg names: String): JsonObject? = element(*names) as? JsonObject
private fun JsonObject.arrayValue(vararg names: String): List<JsonElement> =
    (element(*names) as? JsonArray).orEmpty()
private fun JsonObject.intArray(vararg names: String): List<Int> = arrayValue(*names).mapNotNull {
    val primitive = it as? JsonPrimitive
    primitive?.intOrNull ?: primitive?.contentOrNull?.toIntOrNull()
}

private fun JsonObject.isAuthenticationFailure(): Boolean {
    val code = intValue("code")
    if (code == 10105) return false
    if (code in setOf(401, 403, 10002, 10003, 10004, 10005)) return true
    val message = stringValue("msg", "message").orEmpty().lowercase()
    return listOf("未登录", "登录失效", "登录过期", "token失效", "unauthorized", "session expired")
        .any(message::contains)
}

private fun Throwable.isHttpAuthenticationFailure(): Boolean = causeChain().any {
    it is RisingStonesHttpException.ServerResponse && it.statusCode in setOf(401, 403)
}

private fun Throwable.isHttpIdentityConflict(): Boolean = causeChain().any { cause ->
    cause is RisingStonesHttpException.ServerResponse && runCatching {
        Json.parseToJsonElement(cause.responseBody.decodeToString()).jsonObject.intValue("code") == 10105
    }.getOrDefault(false)
}

private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
private fun String.formEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
