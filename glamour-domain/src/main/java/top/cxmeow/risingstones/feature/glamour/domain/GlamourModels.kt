package top.cxmeow.risingstones.feature.glamour.domain

import java.time.Instant

enum class GlamourListSource { Community, Favorites, Profile }
enum class GlamourListOrder(val wireValue: String?) {
    Default(null), Hottest("hottest"), Latest("latest"),
}

data class GlamourFilter(
    val order: GlamourListOrder = GlamourListOrder.Default,
    val raceId: Int? = null,
    val genderId: Int? = null,
    val createTime: String? = null,
)

data class GlamourSearchSelection(
    val keywords: String,
    val searchByEquipment: Boolean = false,
    val searchByGlasses: Boolean = false,
    val searchByOrnament: Boolean = false,
    val displayTitle: String = keywords,
)

data class GlamourListRequest(
    val source: GlamourListSource = GlamourListSource.Community,
    val page: Int = 1,
    val limit: Int = 12,
    val filter: GlamourFilter = GlamourFilter(),
    val search: GlamourSearchSelection? = null,
    val favoriteFolderId: Int? = null,
    val authorId: String? = null,
)

data class GlamourListPage(
    val items: List<GlamourListingSummary>,
    val currentPage: Int,
    val hasNextPage: Boolean,
)

data class GlamourFavoriteFolder(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
    val isPublic: Boolean,
    val itemCount: Int,
)

data class GlamourProfileStatistics(val posts: Int, val likes: Int, val favorites: Int)
data class GlamourAuthorProfile(
    val author: GlamourAuthor,
    val profile: String?,
    val followingCount: Int,
    val followerCount: Int,
    val relation: Int,
) {
    val isFollowing: Boolean get() = relation in 2..3
}
data class GlamourRace(val id: Int, val name: String)

data class GlamourEquipmentSearchResult(
    val id: Int,
    val name: String,
    val description: String,
    val iconId: String?,
    val jobNames: List<String>,
)

data class GlamourAccessorySearchResult(
    val id: Int,
    val name: String,
    val description: String,
    val iconId: String?,
)

data class GlamourGlassesSearchGroup(
    val id: Int,
    val name: String,
    val accessories: List<GlamourAccessorySearchResult>,
)

data class GlamourAuthor(
    val id: String?,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
)

data class GlamourListingSummary(
    val id: Int,
    val title: String,
    val description: String,
    val imageUrls: List<String>,
    val author: GlamourAuthor,
    val likes: Int,
    val favorites: Int,
    val isLiked: Boolean,
    val isFavorite: Boolean,
    val createdAt: Instant?,
    val jobIds: List<Int>,
    val raceIds: List<Int>,
    val genderIds: List<Int>,
    /** Whether the listing is eligible for the fashion coupon badge. */
    val isCouponEligible: Boolean = false,
)

data class GlamourDetail(
    val id: Int,
    val title: String,
    val description: String,
    val imageUrls: List<String>,
    val author: GlamourAuthor,
    val likes: Int,
    val favorites: Int,
    val isLiked: Boolean,
    val isFavorite: Boolean,
    val createdAt: Instant?,
    val raceNames: List<String>,
    val equipments: List<GlamourEquipment>,
    val faceAccessory: GlamourAccessory?,
    val fashionAccessory: GlamourAccessory?,
    val isCouponEligible: Boolean = false,
    val couponInviteCode: String? = null,
    val isCouponClaimed: Boolean = false,
    val isFollowingAuthor: Boolean = false,
)

data class GlamourAccessory(val id: Int, val name: String, val iconId: String?)

data class GlamourEquipment(
    val slot: String,
    val equipmentId: Int?,
    val name: String?,
    val iconId: String?,
    val dyeIds: List<Int>,
    val dyes: List<GlamourDye>,
) {
    val dyeSlotCount: Int get() = dyeIds.size.coerceAtMost(2)
    fun dye(index: Int): GlamourDye? = dyeIds.getOrNull(index)?.takeIf { it >= 0 }
        ?.let { id -> dyes.firstOrNull { it.id == id } }
}

data class GlamourDye(val id: Int, val name: String, val color: String?)

sealed class GlamourException(message: String) : Exception(message) {
    data object AuthenticationRequired : GlamourException("Rising Stones identity is required")
    data object MissingPayload : GlamourException("The Rising Stones glamour service returned no data")
    data object MissingDefaultFavoriteFolder : GlamourException("The default favorite folder is unavailable")
    data class Business(val code: Int, val reason: String?) :
        GlamourException(reason ?: "The Rising Stones glamour service is unavailable ($code)")
}

interface GlamourService {
    val hasCommunityIdentity: Boolean
    suspend fun fetchGlamours(request: GlamourListRequest): GlamourListPage
    suspend fun fetchFavoriteFolders(authorId: String? = null): List<GlamourFavoriteFolder>
    suspend fun createFavoriteFolder(name: String, isPublic: Boolean)
    suspend fun deleteFavoriteFolder(id: Int)
    suspend fun fetchProfileStatistics(authorId: String? = null): GlamourProfileStatistics
    suspend fun fetchAuthorProfile(authorId: String): GlamourAuthorProfile
    suspend fun followAuthor(authorId: String)
    suspend fun cancelFollowAuthor(authorId: String)
    suspend fun fetchRaces(): List<GlamourRace>
    suspend fun searchEquipment(name: String, page: Int = 1): List<GlamourEquipmentSearchResult>
    suspend fun searchGlasses(name: String): List<GlamourGlassesSearchGroup>
    suspend fun searchOrnaments(name: String): List<GlamourAccessorySearchResult>
    suspend fun fetchDetail(id: Int): GlamourDetail
    suspend fun favorite(id: Int)
    suspend fun cancelFavorite(id: Int)
    suspend fun toggleLike(id: Int): Boolean
    suspend fun claimCoupon(inviteCode: String, glamourId: Int) {
        throw UnsupportedOperationException("Coupon claiming is unavailable")
    }
}
