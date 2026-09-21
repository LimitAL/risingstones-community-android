package top.cxmeow.risingstones.feature.recruitment.domain

/** Optional, read-only access to a role-play recruitment's members and activities. */
interface RolePlayDirectoryService : DutyRecruitmentService {
    suspend fun fetchRolePlayMemberPage(query: RolePlayMemberQuery): RolePlayMemberPage
    suspend fun fetchRolePlayMemberDetail(id: Int): RolePlayMemberDetail
    suspend fun fetchRolePlayActivities(recruitmentId: Int): List<RolePlayActivity>
    suspend fun fetchRolePlayActivityDetail(id: Int): RolePlayActivityDetail
}

data class RolePlayMemberQuery(
    val recruitmentId: Int,
    val page: Int = 1,
    val limit: Int = 10,
    /** Opaque first-page value to send unchanged with subsequent pages. */
    val pageTime: String? = null,
)

data class RolePlayMemberPage(
    val items: List<RolePlayRecruitmentMember>,
    val page: Int,
    val hasMore: Boolean,
    val pageTime: String?,
)

data class RolePlayMemberDetail(
    val id: Int,
    /** Null when the response does not identify its parent recruitment. */
    val recruitmentId: Int?,
    val name: String,
    val identity: String?,
    val avatarUrl: String?,
    /** Plain text; markup-looking text and entities must remain literal. */
    val description: String?,
    val detailImageUrl: String?,
)

data class RolePlayActivity(
    val id: Int,
    val recruitmentId: Int?,
    val name: String,
    val coverUrl: String?,
    /** Original date value. A timezone must not be inferred from local date text. */
    val beginTime: String?,
    val endTime: String?,
    /** Publication state: 0 is hidden, 1 is published; unknown values are retained. */
    val status: Int?,
    /** Activity state: 0 is upcoming, 1 is ongoing; unknown values are retained. */
    val activityStatus: Int?,
)

data class RolePlayActivityDetail(
    val activity: RolePlayActivity,
    /** Original official markup for consumers that supply their own renderer. */
    val contentHtml: String,
    val bodyBlocks: List<RolePlayActivityBodyBlock>,
    /** The basic native blocks cannot fully represent all supplied markup. */
    val hasUnsupportedContent: Boolean,
)

sealed interface RolePlayActivityBodyBlock {
    /** Basic formatting and HTTPS links, with executable attributes removed. */
    data class Html(val contentHtml: String) : RolePlayActivityBodyBlock
    data class Image(
        val url: String,
        val alternativeText: String? = null,
        val linkUrl: String? = null,
    ) : RolePlayActivityBodyBlock
}
