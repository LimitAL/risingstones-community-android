package top.cxmeow.risingstones.feature.profile.domain

/** Additive read projection: no change to published ProfileService/Person constructors or copy ABI. */
data class ProfileUserSearchEntry(
    val uuid: String, val name: String, val areaName: String, val groupName: String,
    val avatarUrl: String?, val biography: String?, val followerCount: Long?,
)
/** Search supplies an array, not a source total. hasMore is only the full-wire-page heuristic. */
data class ProfileUserSearchPage(val users: List<ProfileUserSearchEntry>, val page: Int, val hasMore: Boolean)
interface ProfileUserSearchService {
    suspend fun searchUsers(keywords: String, page: Int = 1, limit: Int = 20): ProfileUserSearchPage
}
