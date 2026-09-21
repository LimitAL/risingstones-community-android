package top.cxmeow.risingstones.feature.profile.data

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.network.*

class ProfileApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProfileService {
    override val canRead: Boolean get() = RisingStonesCapability.AccountRead in sessionProvider.capabilities

    override suspend fun fetchProfile(owner: ProfileOwner): CommunityProfile =
        profile(get("userInfo/getUserInfo", owner.parameters()), owner == ProfileOwner.Self)

    override suspend fun readSection(query: ProfileListQuery): ProfilePage {
        val section = query.section
        require(section != ProfileSection.Overview)
        val favorites = section in listOf(ProfileSection.FavoritePosts, ProfileSection.FavoriteGuides)
        if (favorites && query.owner != ProfileOwner.Self) throw ProfileException.Unavailable
        val page = query.page.coerceAtLeast(1)
        val params = query.owner.parameters() + listOf(q("page", page), q("limit", PageSize)) + when (section) {
            ProfileSection.Posts, ProfileSection.FavoritePosts -> listOf(q("type", 1))
            ProfileSection.Guides, ProfileSection.FavoriteGuides -> listOf(q("type", 2))
            else -> emptyList()
        }
        val path = when (section) {
            ProfileSection.Following -> "userRelation/followList"
            ProfileSection.Followers -> "userRelation/fansList"
            ProfileSection.Dynamics -> "userInfo/getUserDynamic"
            ProfileSection.FavoritePosts, ProfileSection.FavoriteGuides -> "userInfo/myStarPosts"
            else -> "userInfo/getUserPosts"
        }
        val data = get(path, params)
        val rows = data["rows"] as? JsonArray ?: throw ProfileException.InvalidResponse
        val items = rows.map { element ->
            val row = element as? JsonObject ?: throw ProfileException.InvalidResponse
            if (section in listOf(ProfileSection.Following, ProfileSection.Followers)) person(row)
            else content(row, section)
        }
        return ProfilePage(items, page, data.number("count")?.let { page.toLong() * PageSize < it }
            ?: (rows.size >= PageSize))
    }

    private suspend fun get(path: String, parameters: List<RisingStonesApiQueryItem>): JsonObject {
        if (!canRead) throw ProfileException.Unavailable
        return try {
            val authorizer = sessionProvider.currentAuthorizer() ?: throw ProfileException.AuthenticationRequired
            try { request(authorizer, path, parameters) }
            catch (_: ProfileException.AuthenticationRequired) {
                val refreshed = sessionProvider.refreshAuthorizer() ?: throw ProfileException.AuthenticationRequired
                if (!canRead) throw ProfileException.Unavailable
                request(refreshed, path, parameters)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: ProfileException) { throw error
        } catch (_: Exception) { throw ProfileException.Network }
    }

    private suspend fun request(authorizer: RisingStonesRequestAuthorizer, endpoint: String,
        parameters: List<RisingStonesApiQueryItem>): JsonObject {
        val path = "api/home/$endpoint"
        val headers = linkedMapOf<String, String>()
        authorizer.authorize(RisingStonesRequestContext(path, RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.AccountRead), RisingStonesHeaderSink { key, value -> headers[key] = value })
        val response = try { client.execute(RisingStonesApiRequest(path, query = parameters, headers = headers))
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            val status = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<RisingStonesHttpException.ServerResponse>().firstOrNull()?.statusCode
            if (status in listOf(401, 403)) throw ProfileException.AuthenticationRequired
            throw ProfileException.Network
        }
        if (response.statusCode in listOf(401, 403)) throw ProfileException.AuthenticationRequired
        if (response.statusCode !in 200..299) throw ProfileException.Network
        val envelope = try { json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) { null } ?: throw ProfileException.InvalidResponse
        val code = envelope.number("code")
        if (RisingStonesResponsePolicy.accepts(code)) {
            return envelope["data"] as? JsonObject ?: throw ProfileException.InvalidResponse
        }
        when (code) {
            10001, 10403, 10105 -> throw ProfileException.AuthenticationRequired
            null -> throw ProfileException.InvalidResponse
            else -> throw ProfileException.Business(code)
        }
    }
}

private fun profile(row: JsonObject, self: Boolean): CommunityProfile {
    val id = row.text("uuid")?.takeIf(String::isNotBlank) ?: throw ProfileException.InvalidResponse
    val detail = (row["characterDetail"] as? JsonArray)?.firstOrNull() as? JsonObject
    fun visible(flag: String) = self || row.number(flag) == 1
    val fields = listOf(
        ProfileFactKind.Guild to "guild_name", ProfileFactKind.CreatedAt to "create_time",
        ProfileFactKind.LastLogin to "last_login_time", ProfileFactKind.PlayTime to "play_time",
        ProfileFactKind.Housing to "house_info", ProfileFactKind.FantasiaUses to "washing_num",
        ProfileFactKind.TreasureDungeonsCleared to "treasure_times", ProfileFactKind.FrontlineDefeats to "kill_times",
        ProfileFactKind.IslandSanctuaryRank to "newrank", ProfileFactKind.CrystalRank to "crystal_rank",
        ProfileFactKind.FishingCasts to "fish_times",
    )
    val facts = fields.map { (kind, field) ->
        val flag = if (kind == ProfileFactKind.Guild) "guild_publish" else "${field}_publish"
        // Never expose a hidden value through a supposedly presentation-only visibility flag.
        val value = if (visible(flag)) detail?.text(field)?.takeIf(String::isNotBlank) else null
        ProfileFact(kind, if (!visible(flag)) ProfileVisibility.Private else if (value == null)
            ProfileVisibility.Unavailable else ProfileVisibility.Visible, value)
    }
    val careerRows = row["careerLevel"] as? JsonArray
    val achievementRows = row["achieveInfo"] as? JsonArray
    val careers = if (!visible("career_publish")) emptyList() else careerRows.orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.text("career") ?: return@mapNotNull null
        val level = item.number("character_level") ?: return@mapNotNull null
        ProfileCareer(name, level)
    }
    val achievements = if (!visible("achieve_publish")) emptyList() else achievementRows.orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.text("achieve_name") ?: return@mapNotNull null
        val achievementId = item.text("achieve_id") ?: return@mapNotNull null
        ProfileAchievement(achievementId, name, item.text("achieve_detail").orEmpty(), item.text("achieve_time"))
    }
    val follow = row["followFansiNum"] as? JsonObject
    return CommunityProfile(id, row.text("character_name").orEmpty(), row.text("area_name").orEmpty(),
        row.text("group_name").orEmpty(), images(row.text("avatar")).firstOrNull(), row.text("profile").orEmpty(),
        self, follow?.number("followNum")?.coerceAtLeast(0) ?: 0, follow?.number("fansNum")?.coerceAtLeast(0) ?: 0,
        row.number("beLikedNum")?.coerceAtLeast(0) ?: 0, facts,
        visibility(visible("career_publish"), careerRows), careers,
        visibility(visible("achieve_publish"), achievementRows), achievements)
}
private fun visibility(visible: Boolean, rows: JsonArray?) = when {
    !visible -> ProfileVisibility.Private
    rows == null -> ProfileVisibility.Unavailable
    else -> ProfileVisibility.Visible
}
private fun person(row: JsonObject) = ProfileListItem.Person(
    row.text("uuid")?.takeIf(String::isNotBlank) ?: throw ProfileException.InvalidResponse,
    row.text("character_name").orEmpty(), row.text("area_name").orEmpty(), row.text("group_name").orEmpty(),
    images(row.text("avatar")).firstOrNull(), row.text("profile").orEmpty())

private fun content(row: JsonObject, section: ProfileSection): ProfileListItem.Content {
    val kind = when (section) {
        ProfileSection.Dynamics -> ProfileContentKind.Dynamic
        ProfileSection.Guides, ProfileSection.FavoriteGuides -> ProfileContentKind.Guide
        else -> ProfileContentKind.Post
    }
    val favorite = section in listOf(ProfileSection.FavoritePosts, ProfileSection.FavoriteGuides)
    val id = row.number(if (kind == ProfileContentKind.Dynamic) "id" else "posts_id")?.takeIf { it > 0 }
        ?: throw ProfileException.InvalidResponse
    return ProfileListItem.Content(ProfileContentTarget(kind, id), row.text("title").orEmpty(),
        row.text(if (kind == ProfileContentKind.Dynamic) "mask_content" else "content_pre").orEmpty(),
        images(row.text(if (kind == ProfileContentKind.Dynamic) "pic_url" else "cover_pic")),
        row.text(if (favorite) "posts_created_at" else "created_at"))
}

private const val PageSize = 10
private fun ProfileOwner.parameters() = when (this) {
    ProfileOwner.Self -> emptyList()
    is ProfileOwner.User -> listOf(q("uuid", uuid))
}
private fun q(key: String, value: Any) = RisingStonesApiQueryItem(key, value.toString())
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String) = text(key)?.toIntOrNull()
private fun images(value: String?) = value.orEmpty().split(',').map(String::trim).filter { url ->
    val uri = runCatching { URI(url) }.getOrNull()
    uri?.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        uri.host?.lowercase() in setOf("ff14risingstones.gcloud.com.cn", "ff14risingstones.web.sdo.com",
            "ff14-eo.web.sdo.com", "static.web.sdo.com")
}.distinct()
