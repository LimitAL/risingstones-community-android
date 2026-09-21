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
sealed interface ProfileListItem {
    val key: String
    data class Content(
        val target: ProfileContentTarget,
        val title: String,
        val summaryHtml: String,
        val imageUrls: List<String>,
        val createdAt: String?,
    ) : ProfileListItem { override val key: String get() = "${target.kind}:${target.id}" }
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
