package top.cxmeow.risingstones.feature.profile.domain

/** Self deliberately omits uuid; another profile cannot access the signed-in user's favorites. */
sealed interface ProfileOwner {
    data object Self : ProfileOwner
    data class User(val uuid: String) : ProfileOwner { init { require(uuid.isNotBlank()) } }
}

enum class ProfileVisibility { Visible, Private, Unavailable }
enum class ProfileFactKind {
    Guild, CreatedAt, LastLogin, PlayTime, Housing, FantasiaUses,
    TreasureDungeonsCleared, FrontlineDefeats, IslandSanctuaryRank, CrystalRank, FishingCasts,
}
data class ProfileFact(val kind: ProfileFactKind, val visibility: ProfileVisibility, val value: String?)
data class ProfileCareer(val name: String, val level: Int)
data class ProfileAchievement(val id: String, val name: String, val description: String, val achievedAt: String?)
data class CommunityProfile(
    val uuid: String,
    val name: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
    val biography: String,
    val isSelf: Boolean,
    val followingCount: Int,
    val followerCount: Int,
    val likedCount: Int,
    val facts: List<ProfileFact> = emptyList(),
    val careerVisibility: ProfileVisibility = ProfileVisibility.Unavailable,
    val careers: List<ProfileCareer> = emptyList(),
    val achievementVisibility: ProfileVisibility = ProfileVisibility.Unavailable,
    val achievements: List<ProfileAchievement> = emptyList(),
)

enum class ProfileSection { Overview, Posts, Guides, Dynamics, FavoritePosts, FavoriteGuides, Following, Followers }
enum class ProfileContentKind { Post, Guide, Dynamic }
data class ProfileContentTarget(val kind: ProfileContentKind, val id: Int)
/** Optional facts from a dynamic row, never the profile aggregate or an attachment's likes. */
data class ProfileDynamicLikeFacts(val isLiked: Boolean?, val count: Long?) {
    init { require(count == null || count >= 0) }
}
sealed interface ProfileListItem {
    val key: String
    data class Content @JvmOverloads constructor(
        val target: ProfileContentTarget,
        val title: String,
        val summaryHtml: String,
        val imageUrls: List<String>,
        val createdAt: String?,
        val sourceLikeFacts: ProfileDynamicLikeFacts? = null,
    ) : ProfileListItem {
        override val key: String get() = "${target.kind}:${target.id}"
    /** Original JVM copy entry points retain the source snapshot and original mask meanings. */
    fun copy(target: ProfileContentTarget, title: String, summaryHtml: String, imageUrls: List<String>, createdAt: String?): Content = Content(target, title, summaryHtml, imageUrls, createdAt, sourceLikeFacts)
    companion object {
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun `copy$default`(source: Content, target: ProfileContentTarget?, title: String?, summaryHtml: String?, imageUrls: List<String>?, createdAt: String?, mask: Int, marker: Any?): Content =
            source.copy(if (mask and 1 != 0) source.target else requireNotNull(target),
                if (mask and 2 != 0) source.title else requireNotNull(title),
                if (mask and 4 != 0) source.summaryHtml else requireNotNull(summaryHtml),
                if (mask and 8 != 0) source.imageUrls else requireNotNull(imageUrls),
                if (mask and 16 != 0) source.createdAt else createdAt)
    }
    }
    data class Person(
        val uuid: String,
        val name: String,
        val areaName: String,
        val groupName: String,
        val avatarUrl: String?,
        val biography: String,
    ) : ProfileListItem { override val key: String get() = uuid }
}
data class ProfileListQuery(val owner: ProfileOwner, val section: ProfileSection, val page: Int = 1)
data class ProfilePage(val items: List<ProfileListItem>, val page: Int, val hasMore: Boolean)

interface ProfileService {
    val canRead: Boolean
    suspend fun fetchProfile(owner: ProfileOwner): CommunityProfile
    /** Followers may acknowledge the new-follower reminder. Invoke only after an explicit user action. */
    suspend fun readSection(query: ProfileListQuery): ProfilePage
}

sealed class ProfileException(message: String) : Exception(message) {
    data object AuthenticationRequired : ProfileException("Profile authentication required")
    data object Unavailable : ProfileException("Profile capability unavailable")
    data object InvalidResponse : ProfileException("Invalid profile response")
    data object Network : ProfileException("Profile transport failed")
    class Business(val code: Int) : ProfileException("Profile request failed")
}

/** Explicit official fc_id only. Missing/private IDs never imply that this user has no guild. */
data class ProfileGuildReference(val id: String, val name: String?) {
    init { require(id.isNotEmpty() && id.all { it in '0'..'9' } && id.any { it != '0' }) }
}
/** Additive read projection preserves CommunityProfile's published constructors and copy ABI. */
data class ProfileGuildSnapshot(val profile: CommunityProfile, val guild: ProfileGuildReference?)
