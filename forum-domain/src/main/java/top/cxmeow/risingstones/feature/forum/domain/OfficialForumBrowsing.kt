package top.cxmeow.risingstones.feature.forum.domain

/** Optional browsing contract; existing service implementations keep their original API. */
interface OfficialForumBrowsingService : OfficialForumService {
    suspend fun fetchCategories(kind: OfficialForumContentKind): List<OfficialForumCategory>
    suspend fun fetchBrowsePage(query: OfficialForumBrowseQuery): OfficialForumBrowsePage
    suspend fun searchBrowsePage(
        query: OfficialForumSearchQuery,
        pageTime: String? = null,
    ): OfficialForumBrowsePage
}

enum class OfficialForumSearchField { Title, Body }

/** Optional full-text search contract with the official title/body search dimensions. */
interface OfficialForumTextSearchService {
    suspend fun searchTextPage(
        query: OfficialForumSearchQuery,
        field: OfficialForumSearchField = OfficialForumSearchField.Title,
        pageTime: String? = null,
    ): OfficialForumBrowsePage
}

data class OfficialForumCategory(
    val part: OfficialForumPartFilter,
    val children: List<OfficialForumCategory> = emptyList(),
)

enum class OfficialForumFeedFilter { Default, Latest, Refined, Pinned }

data class OfficialForumBrowseQuery(
    val list: OfficialForumListQuery = OfficialForumListQuery(),
    val filter: OfficialForumFeedFilter = OfficialForumFeedFilter.Default,
    val pageTime: String? = null,
)

data class OfficialForumBrowsePage(
    val page: OfficialForumPage<OfficialForumPostSummary>,
    val pageTime: String?,
)
