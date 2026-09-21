package top.cxmeow.risingstones.feature.glamour.domain

data class GlamourTag(val id: Int, val name: String)
data class GlamourTagCategory(val id: Int, val name: String, val tags: List<GlamourTag>)
data class GlamourTribe(val id: Int, val raceId: Int, val name: String)

data class GlamourBrowseRequest(
    val listing: GlamourListRequest = GlamourListRequest(),
    val following: Boolean = false,
    val tagIds: Set<Int> = emptySet(),
    val tribeId: Int? = null,
    val pageTime: String? = null,
)

data class GlamourBrowsePage(val page: GlamourListPage, val nextPageTime: String?)

/** Optional extension; existing GlamourService implementations and list contracts remain valid. */
interface GlamourBrowsingService : GlamourService {
    suspend fun fetchBrowsePage(request: GlamourBrowseRequest): GlamourBrowsePage
    suspend fun fetchTagCategories(): List<GlamourTagCategory>
    suspend fun fetchTribes(): List<GlamourTribe>
}

class GlamourTransportException : Exception("Glamour transport unavailable")
