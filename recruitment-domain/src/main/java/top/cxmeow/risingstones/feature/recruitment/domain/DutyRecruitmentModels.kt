package top.cxmeow.risingstones.feature.recruitment.domain

import java.time.Instant

enum class DutyRecruitmentPosition(val wireValue: String) {
    Tank("T"), MainTank("MT"), SubTank("ST"), Healer("H"), Healer1("H1"), Healer2("H2"),
    Damage1("D1"), Damage2("D2"), Damage3("D3"), Damage4("D4");

    companion object {
        fun optionsForTeamComposition(teamComposition: String): List<DutyRecruitmentPosition> =
            when (teamComposition.trim()) {
                "满编小队", "团队" -> listOf(MainTank, SubTank, Healer1, Healer2, Damage1, Damage2, Damage3, Damage4)
                "轻锐小队" -> listOf(Tank, Healer, Damage1, Damage2)
                else -> emptyList()
            }

        fun options(dutyType: String) = if (dutyType.trim() == "多变迷宫") {
            listOf(Tank, Healer, Damage1, Damage2)
        } else {
            listOf(MainTank, SubTank, Healer1, Healer2, Damage1, Damage2, Damage3, Damage4)
        }
    }
}

fun dutyRecruitmentTeamCompositionForType(type: String): String = when (type.trim()) {
    "绝境战", "零式" -> "满编小队"
    "多变迷宫" -> "轻锐小队"
    "诛灭战" -> "团队"
    else -> ""
}

data class DutyRecruitmentListQuery(
    val page: Int = 1,
    val limit: Int = 20,
    val dutyName: String = "",
    val dutyType: String = "",
    val position: DutyRecruitmentPosition? = null,
)

data class DutyRecruitmentListPage(
    val items: List<DutyRecruitmentSummary>,
    val totalCount: Int,
    val currentPage: Int,
)

data class DutyRecruitmentLabel(val id: String, val name: String, val weight: Int)
data class DutyRecruitmentJob(val id: String, val name: String, val iconUrl: String?, val roleType: String?)

data class DutyRecruitmentRoleCounts(
    val mt: Int = 0,
    val st: Int = 0,
    val h1: Int = 0,
    val h2: Int = 0,
    val h: Int = 0,
    val t: Int = 0,
    val d1: Int = 0,
    val d2: Int = 0,
    val d3: Int = 0,
    val d4: Int = 0,
) {
    fun nonEmptyEntries(): List<Pair<String, Int>> = listOf(
        "MT" to mt, "ST" to st, "H1" to h1, "H2" to h2, "H" to h, "T" to t,
        "D1" to d1, "D2" to d2, "D3" to d3, "D4" to d4,
    ).filter { it.second > 0 }
}

data class DutyRecruitmentSummary(
    val id: Int,
    val uuid: String?,
    val avatarUrl: String?,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val targetAreaName: String,
    val dutyType: String,
    val dutyName: String,
    val teamComposition: String,
    val progress: String,
    val schedule: String,
    val strategy: String,
    val beginTime: Instant?,
    val endTime: Instant?,
    val responseCount: Int,
    val status: Int,
    val labels: List<DutyRecruitmentLabel>,
    val jobs: List<DutyRecruitmentJob>,
    val requiredJobCodes: List<String>,
    val roleCounts: DutyRecruitmentRoleCounts,
    val updatedAt: Instant?,
)

data class DutyRecruitmentDetail(
    val summary: DutyRecruitmentSummary,
    val targetGroupName: String?,
    val teamDetail: String,
    val recruitRequirements: String,
    val strategyDescription: String,
    val contactInfo: String,
    val dueDay: Int,
    val createdAt: Instant?,
    val lastResponseTime: Instant?,
    val isResponded: Boolean,
    val isShare: Boolean,
    val relation: Int?,
    val createdBy: String?,
)

data class DutyRecruitmentDutyConfig(
    val id: String,
    val dutyType: String,
    val dutyName: String,
    val teamComposition: String,
    val weight: Int,
)

data class DutyRecruitmentCatalogs(
    val jobs: Map<String, DutyRecruitmentJob> = emptyMap(),
    val duties: List<DutyRecruitmentDutyConfig> = emptyList(),
) {
    val dutyTypes: List<String>
        get() = duties.map { it.dutyType }.distinct()
    fun dutyNames(type: String) = duties.filter { type.isBlank() || it.dutyType == type }
        .sortedByDescending(DutyRecruitmentDutyConfig::weight).map { it.dutyName }.distinct()
}

enum class CommunityRecruitmentKind(val listEndpoint: String, val detailEndpoint: String) {
    Beginner("api/home/recruit/recruitNeList", "api/home/recruit/getNeDetail"),
    Guild("api/home/recruit/recruitGuildList", "api/home/recruit/getRecruitGuildDetail"),
    Other("api/home/recruit/recruitOtherList", "api/home/recruit/getOtherDetail"),
    RolePlay("api/home/recruit/recruitRpList", "api/home/recruit/getRpDetail");

    val requiresAuthentication: Boolean get() = this == Guild
}

data class CommunityRecruitmentQuery(
    val kind: CommunityRecruitmentKind,
    val page: Int = 1,
    val limit: Int = 20,
    val keyword: String = "",
    val areaId: String = "",
    val groupId: String = "",
    val styleIds: List<String> = emptyList(),
    val identity: String = "",
    val guildLabelIds: List<String> = emptyList(),
    val activeMemberCounts: String = "",
    val categoryIds: List<String> = emptyList(),
    val rolePlayTypes: List<String> = emptyList(),
    val rolePlayStatus: String = "",
    val order: String = "",
)

data class CommunityRecruitmentPage(
    val items: List<CommunityRecruitmentSummary>,
    val totalCount: Int,
    val currentPage: Int,
)

enum class BeginnerRecruitmentIdentity(val wireValue: Int) { Mentor(1), Newcomer(2) }
data class BeginnerRecruitmentStyle(val name: String, val iconUrl: String?)

data class BeginnerRecruitmentCard(
    val publisherName: String,
    val publisherServer: String?,
    val identity: BeginnerRecruitmentIdentity?,
    val title: String,
    val description: String?,
    val styles: List<BeginnerRecruitmentStyle>,
    val targetServer: String?,
    val weekdayTime: String?,
    val weekendTime: String?,
    val isResponded: Boolean,
    val recruiterContactInfo: String?,
)

data class GuildRecruitmentCard(
    val guildName: String,
    val guildTag: String?,
    val server: String?,
    val weekdayTime: String?,
    val weekendTime: String?,
    val activeMembers: String?,
    val recruitmentTarget: String?,
    val labels: List<String>,
    val coverUrl: String?,
)

data class OtherRecruitmentCard(
    val categoryName: String,
    val title: String,
    val description: String?,
    val publisherName: String,
    val publisherServer: String?,
    val publisherAvatarUrl: String?,
    val coverUrl: String?,
    val targetServer: String?,
    val responseCount: Int?,
)

enum class RolePlayRecruitmentType(val wireValue: String) { None("0"), Light("1"), Medium("2"), Heavy("3") }
data class RolePlayRecruitmentCard(
    val name: String,
    val types: List<RolePlayRecruitmentType>,
    val profile: String?,
    val openTime: String?,
    val labels: List<String>,
    val coverUrl: String?,
)

data class CommunityRecruitmentSummary(
    val id: Int,
    val kind: CommunityRecruitmentKind,
    val title: String,
    val authorName: String?,
    val avatarUrl: String?,
    val sourceLocation: String?,
    val targetLocation: String?,
    val summary: String?,
    val coverUrl: String?,
    val footerImageUrls: List<String> = emptyList(),
    val activityCount: Int? = null,
    val updatedAt: Instant? = null,
    val beginner: BeginnerRecruitmentCard? = null,
    val guild: GuildRecruitmentCard? = null,
    val other: OtherRecruitmentCard? = null,
    val rolePlay: RolePlayRecruitmentCard? = null,
)

enum class CommunityRecruitmentInformationKind {
    Identity, Style, GuildTag, ActiveMembers, TargetMembers, RolePlayType, ActivityStatus,
    Address, ActivityCount, WeekdaySchedule, WeekendSchedule, CreatedAt, OpenTime, StarCount,
}
data class CommunityRecruitmentInformation(val kind: CommunityRecruitmentInformationKind, val value: String)
enum class CommunityRecruitmentContentKind { Description, Profile, Requirements, Schedule }
data class CommunityRecruitmentContent(
    val kind: CommunityRecruitmentContentKind,
    val text: String,
    val html: String,
    val imageUrls: List<String> = emptyList(),
)
data class CommunityRecruitmentDetail(
    val summary: CommunityRecruitmentSummary,
    val information: List<CommunityRecruitmentInformation>,
    val content: List<CommunityRecruitmentContent>,
)

data class RolePlayRecruitmentMember(
    val id: Int,
    val name: String,
    val identity: String?,
    val avatarUrl: String?,
    val description: String?,
    val detailImageUrls: List<String>,
)
data class RolePlayRecruitmentSubcomment(
    val id: String,
    val authorName: String,
    val avatarUrl: String?,
    val replyTargetName: String?,
    val content: String,
    val imageUrls: List<String>,
    /** Original mask_content markup retained for the iOS-style rich renderer. */
    val contentHtml: String = "",
)
data class RolePlayRecruitmentReview(
    val id: String,
    val authorName: String,
    val avatarUrl: String?,
    val location: String?,
    val content: String,
    val score: String?,
    val likeCount: Int,
    val isLiked: Boolean,
    val createdAt: Instant?,
    val imageUrls: List<String>,
    val childCount: Int,
    val childPreviewReplies: List<RolePlayRecruitmentSubcomment> = emptyList(),
    /** Original mask_content markup retained for the iOS-style rich renderer. */
    val contentHtml: String = "",
)
data class RolePlayRecruitmentReviewPage(val items: List<RolePlayRecruitmentReview>, val page: Int, val hasMore: Boolean)
data class RolePlayRecruitmentSubcommentPage(val items: List<RolePlayRecruitmentSubcomment>, val page: Int, val hasMore: Boolean)
data class RolePlayRecruitmentRating(val counts: List<Int>) {
    val totalCount: Int get() = counts.sum()
    val averageScore: Double get() = if (totalCount == 0) 0.0 else
        counts.mapIndexed { index, count -> (index + 1) * count }.sum().toDouble() / totalCount
}

data class CommunityRecruitmentFilterOption(val id: String, val name: String)
data class CommunityRecruitmentServer(val id: String, val name: String)
data class CommunityRecruitmentArea(val id: String, val name: String, val servers: List<CommunityRecruitmentServer>)
data class CommunityRecruitmentFilterCatalog(
    val styles: List<CommunityRecruitmentFilterOption> = emptyList(),
    val guildLabels: List<CommunityRecruitmentFilterOption> = emptyList(),
    val categories: List<CommunityRecruitmentFilterOption> = emptyList(),
    val areas: List<CommunityRecruitmentArea> = emptyList(),
)

sealed class DutyRecruitmentException(message: String) : Exception(message) {
    data object AuthenticationRequired : DutyRecruitmentException("Rising Stones identity is required")
    data object MissingPayload : DutyRecruitmentException("The recruitment service returned no data")
    data class Business(val code: Int, val reason: String?) :
        DutyRecruitmentException(reason ?: "The recruitment service is unavailable ($code)")
}

interface DutyRecruitmentService {
    val hasCommunityIdentity: Boolean
    suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage
    suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail
    suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String?
    suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String): String?
    suspend fun fetchCatalogs(): DutyRecruitmentCatalogs
    suspend fun fetchCommunityRecruitments(query: CommunityRecruitmentQuery): CommunityRecruitmentPage
    suspend fun fetchCommunityRecruitmentDetail(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentDetail
    suspend fun fetchCommunityFilterCatalog(kind: CommunityRecruitmentKind): CommunityRecruitmentFilterCatalog
    suspend fun fetchRolePlayMembers(id: Int): List<RolePlayRecruitmentMember>
    suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int): RolePlayRecruitmentReviewPage
    suspend fun fetchRolePlaySubcomments(rootParentId: String, page: Int, limit: Int): RolePlayRecruitmentSubcommentPage
    suspend fun fetchRolePlayRating(id: Int): RolePlayRecruitmentRating
    suspend fun likeRolePlayReview(id: String): Int
}
