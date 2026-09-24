package top.cxmeow.risingstones.feature.recruitment.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesIdentityConflictResolver
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentAuthorService
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentActionEligibilityService
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentAuthorPage
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentAuthorDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayReviewAuthorPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlaySubcommentAuthorPage
import top.cxmeow.risingstones.feature.recruitment.domain.BeginnerRecruitmentCard
import top.cxmeow.risingstones.feature.recruitment.domain.BeginnerRecruitmentIdentity
import top.cxmeow.risingstones.feature.recruitment.domain.BeginnerRecruitmentStyle
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentArea
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentContent
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentContentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentFilterOption
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentInformation
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentInformationKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentInteractionDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentKind
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentPage
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentQuery
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentServer
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentCatalogs
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentBrowseQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentFilterCatalog
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDutyConfig
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentException
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentJob
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentInteractionDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentLabel
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListPage
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentListQuery
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentRoleCounts
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentBrowsingService
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentResponseEligibilityService
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.GuildRecruitmentCard
import top.cxmeow.risingstones.feature.recruitment.domain.OtherRecruitmentCard
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentCard
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentMember
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivity
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivityDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayDirectoryService
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberQuery
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentRating
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReview
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewOrder
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentReviewQuery
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentSubcomment
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentSubcommentPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentType
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy

class DutyRecruitmentApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val temporarySessionId: String = UUID.randomUUID().toString(),
) : RecruitmentBrowsingService, RecruitmentResponseEligibilityService, RecruitmentActionEligibilityService,
    RolePlayDirectoryService, RecruitmentAuthorService {
    private val rolePlayDirectory = RolePlayDirectoryApiReader(temporarySessionId) { perform(it) }

    override val canPerformAuthenticatedWrites: Boolean
        get() = RisingStonesCapability.RecruitmentWrite in sessionProvider.capabilities

    override val canAttemptAuthenticatedWrites: Boolean
        get() = (sessionProvider as? RisingStonesExplicitCapabilityProvider)
            ?.canAttemptCapability(RisingStonesCapability.RecruitmentWrite) == true

    override val hasCommunityIdentity: Boolean
        get() = RisingStonesCapability.RecruitmentAuthenticated in sessionProvider.capabilities

    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentListQuery): DutyRecruitmentListPage =
        fetchDutyRecruitments(DutyRecruitmentBrowseQuery(query))

    override suspend fun fetchDutyRecruitments(query: DutyRecruitmentBrowseQuery): DutyRecruitmentListPage {
        val list = query.list
        val parameters = mutableListOf(q("page", list.page.coerceAtLeast(1)), q("limit", list.limit.coerceAtLeast(1)))
        list.dutyName.takeIf(String::isNotBlank)?.let { parameters += q("fb_name", it) }
        list.dutyType.takeIf(String::isNotBlank)?.let { parameters += q("fb_type", it) }
        val positions = query.positions.distinct().joinToString(",") { it.wireValue }
        if (positions.isNotEmpty()) parameters += q("position", positions)
        query.teamComposition.takeIf { it.isNotBlank() && it != "全部队伍" }?.let { parameters += q("team_composition", it) }
        query.targetAreaId.takeIf { it.isNotBlank() && it != "0" }?.let { parameters += q("target_area_id", it) }
        query.labelIds.distinct().takeIf(List<String>::isNotEmpty)?.let { parameters += q("label", it.joinToString(",")) }
        if (query.teamComposition == "团队") {
            query.allianceTeamKey.takeIf(String::isNotBlank)?.let { parameters += q("son_team_key", it) }
            if (positions.isNotEmpty()) parameters += q("son_team_position", positions)
        }
        val payload = perform(
            RisingStonesApiRequest("api/home/recruit/recruitFbList", query = parameters),
        ).obj("data") ?: throw DutyRecruitmentException.MissingPayload
        val items = payload.requiredArray("rows").mapNotNull(::dutySummary)
        return DutyRecruitmentListPage(items, payload.int("count") ?: items.size, list.page.coerceAtLeast(1))
    }

    override suspend fun fetchDutyRecruitmentDetail(id: Int): DutyRecruitmentDetail = dutyDetail(fetchDutyRow(id))

    override suspend fun fetchDutyInteractionDetail(id: Int): DutyRecruitmentInteractionDetail {
        val row = fetchDutyRow(id)
        return DutyRecruitmentInteractionDetail(dutyDetail(row), isCurrentUserAuthor(row.string("uuid")))
    }

    private suspend fun fetchDutyRow(id: Int): JsonObject = perform(
        RisingStonesApiRequest("api/home/recruit/getRecruitFbDetail", query = listOf(q("id", id))),
    ).obj("data") ?: throw DutyRecruitmentException.MissingPayload

    private fun dutyDetail(row: JsonObject): DutyRecruitmentDetail {
        val summary = dutySummary(row) ?: throw DutyRecruitmentException.MissingPayload
        return DutyRecruitmentDetail(
            summary = summary,
            targetGroupName = row.text("target_group_name", "targetGroupName"),
            teamDetail = row.text("team_detail_mask", "teamDetailMask").orEmpty().plainText(),
            recruitRequirements = row.text("recruit_require_mask", "recruitRequireMask").orEmpty().plainText(),
            strategyDescription = row.text("strategy_desc_mask", "strategyDescMask").orEmpty().plainText(),
            contactInfo = row.text("contact_info_mask", "contactInfoMask").orEmpty(),
            dueDay = row.int("due_day", "dueDay") ?: 0,
            createdAt = row.instant("created_at", "createdAt"),
            lastResponseTime = row.instant("last_response_time", "lastResponseTime"),
            isResponded = row.int("is_response", "isResponse") == 1,
            isShare = row.int("is_share", "isShare") == 1,
            relation = row.int("relation"),
            createdBy = row.text("created_by", "createdBy"),
        )
    }

    override suspend fun respondToDutyRecruitment(id: Int, contactInfo: String): String? =
        response("api/home/recruit/responseRecruitFb", id, contactInfo)

    override suspend fun respondToBeginnerRecruitment(id: Int, contactInfo: String): String? =
        response("api/home/recruit/responseNoviceEntertain", id, contactInfo)

    override suspend fun fetchCatalogs(): DutyRecruitmentCatalogs {
        val jobData = perform(RisingStonesApiRequest("api/home/recruit/getJobConfigList")).obj("data")
            ?: throw DutyRecruitmentException.MissingPayload
        val jobs = jobData.values.flatMap { it as? JsonArray ?: throw DutyRecruitmentException.MissingPayload }
            .mapNotNull { (it as? JsonObject)?.job() }.associateBy(DutyRecruitmentJob::id)
        val duties = perform(RisingStonesApiRequest("api/home/recruit/getFbConfigList")).requiredArray("data")
            .mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                DutyRecruitmentDutyConfig(
                    id = item.text("id") ?: return@mapNotNull null,
                    dutyType = item.text("fb_type", "fbType") ?: return@mapNotNull null,
                    dutyName = item.text("fb_name", "fbName") ?: return@mapNotNull null,
                    teamComposition = item.text("team_composition", "teamComposition").orEmpty(),
                    weight = item.int("weight") ?: 0,
                )
            }
        return DutyRecruitmentCatalogs(jobs, duties)
    }

    override suspend fun fetchDutyFilterCatalog(): DutyRecruitmentFilterCatalog {
        val catalogs = fetchCatalogs()
        val labels = perform(RisingStonesApiRequest("api/home/recruit/fbLabelList"))
            .requiredArray("data").mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                DutyRecruitmentLabel(
                    item.text("id") ?: return@mapNotNull null,
                    item.text("name") ?: return@mapNotNull null,
                    item.int("weight") ?: 0,
                )
            }
        return DutyRecruitmentFilterCatalog(catalogs, labels, fetchAreas())
    }

    override suspend fun fetchCommunityRecruitments(query: CommunityRecruitmentQuery): CommunityRecruitmentPage =
        fetchCommunityRecruitmentsWithAuthors(query).page

    override suspend fun fetchCommunityRecruitmentsWithAuthors(query: CommunityRecruitmentQuery): CommunityRecruitmentAuthorPage {
        val parameters = mutableListOf(q("page", query.page.coerceAtLeast(1)), q("limit", query.limit.coerceAtLeast(1)))
        fun add(name: String, value: String) { value.trim().takeIf(String::isNotEmpty)?.let { parameters += q(name, it) } }
        fun csv(name: String, values: List<String>) = add(name, values.joinToString(","))
        val selectedArea = query.areaId.trim().takeIf { it.isNotEmpty() && it != "0" }
        when (query.kind) {
            CommunityRecruitmentKind.Beginner -> {
                csv("style", query.styleIds); add("identity", query.identity)
                add("target_area_id", query.areaId); add("target_group_id", query.groupId)
            }
            CommunityRecruitmentKind.Guild -> {
                add("guild_name", query.keyword); add("active_member_num", query.activeMemberCounts)
                csv("label", query.guildLabelIds); add("target_area_id", query.areaId); add("target_group_id", query.groupId)
            }
            CommunityRecruitmentKind.Other -> {
                csv("category", query.categoryIds)
                selectedArea?.let {
                    add("target_area_id", it)
                    add("target_group_id", query.groupId)
                }
            }
            CommunityRecruitmentKind.RolePlay -> {
                add("rp_name", query.keyword); csv("rp_type", query.rolePlayTypes)
                add("act_status", query.rolePlayStatus); add("order", query.order)
                selectedArea?.let {
                    add("rp_area_id", it)
                    if (query.groupId.split(',').none { group -> group.trim() == "0" }) {
                        add("rp_group_id", query.groupId)
                    }
                }
            }
        }
        val payload = perform(
            RisingStonesApiRequest(query.kind.listEndpoint, query = parameters),
            required = query.kind.requiresAuthentication,
        ).obj("data") ?: throw DutyRecruitmentException.MissingPayload
        val rows = payload.requiredArray("rows")
        val items = rows.mapNotNull { (it as? JsonObject)?.communitySummary(query.kind) }
        return CommunityRecruitmentAuthorPage(
            CommunityRecruitmentPage(items, payload.int("count") ?: items.size, query.page.coerceAtLeast(1)),
            // The mobile list card does not establish a community author identity.
            // Only the separately verified detail payload exposes this navigation target.
            emptyMap(),
        )
    }

    override suspend fun fetchCommunityRecruitmentDetail(
        id: Int,
        kind: CommunityRecruitmentKind,
    ): CommunityRecruitmentDetail = communityDetail(fetchCommunityRow(id, kind), kind)

    override suspend fun fetchCommunityInteractionDetail(
        id: Int,
        kind: CommunityRecruitmentKind,
    ): CommunityRecruitmentInteractionDetail = fetchCommunityDetailWithAuthor(id, kind).interaction

    override suspend fun fetchCommunityDetailWithAuthor(id: Int, kind: CommunityRecruitmentKind): CommunityRecruitmentAuthorDetail {
        val row = fetchCommunityRow(id, kind)
        return CommunityRecruitmentAuthorDetail(
            CommunityRecruitmentInteractionDetail(communityDetail(row, kind), isCurrentUserAuthor(row.string("uuid"))),
            row.communityAuthorUuid(),
        )
    }

    private suspend fun fetchCommunityRow(id: Int, kind: CommunityRecruitmentKind): JsonObject = perform(
        RisingStonesApiRequest(kind.detailEndpoint, query = listOf(q("id", id), q("tempsuid", temporarySessionId))),
        required = kind.requiresAuthentication,
    ).obj("data") ?: throw DutyRecruitmentException.MissingPayload

    private fun communityDetail(row: JsonObject, kind: CommunityRecruitmentKind): CommunityRecruitmentDetail {
        val summary = row.communityDetailSummary(kind) ?: throw DutyRecruitmentException.MissingPayload
        val information = mutableListOf<CommunityRecruitmentInformation>()
        fun info(type: CommunityRecruitmentInformationKind, vararg keys: String) {
            row.text(*keys)?.let { information += CommunityRecruitmentInformation(type, it) }
        }
        when (kind) {
            CommunityRecruitmentKind.Beginner, CommunityRecruitmentKind.Other -> Unit
            CommunityRecruitmentKind.Guild -> {
                info(CommunityRecruitmentInformationKind.GuildTag, "guild_tag")
                info(CommunityRecruitmentInformationKind.ActiveMembers, "active_member_num")
                info(CommunityRecruitmentInformationKind.TargetMembers, "target_recruit_num")
                info(CommunityRecruitmentInformationKind.Address, "guild_address")
                info(CommunityRecruitmentInformationKind.WeekdaySchedule, "weekday_time")
                info(CommunityRecruitmentInformationKind.WeekendSchedule, "weekend_time")
                row.text("create_time")?.take(10)?.let {
                    information += CommunityRecruitmentInformation(CommunityRecruitmentInformationKind.CreatedAt, it)
                }
            }
            CommunityRecruitmentKind.RolePlay -> {
                info(CommunityRecruitmentInformationKind.Address, "address")
                info(CommunityRecruitmentInformationKind.OpenTime, "open_time")
                row.text("create_time")?.take(10)?.let {
                    information += CommunityRecruitmentInformation(CommunityRecruitmentInformationKind.CreatedAt, it)
                }
                info(CommunityRecruitmentInformationKind.StarCount, "star_count")
            }
        }
        val content = row.text("detail_mask")?.let { html ->
            listOf(
                CommunityRecruitmentContent(
                    CommunityRecruitmentContentKind.Description,
                    html.plainText(),
                    html,
                    html.htmlImageUrls(),
                ),
            )
        }.orEmpty()
        return CommunityRecruitmentDetail(summary, information, content)
    }

    private suspend fun isCurrentUserAuthor(authorUuid: String?): Boolean? {
        if ((!canPerformAuthenticatedWrites && !canAttemptAuthenticatedWrites) || authorUuid.isNullOrBlank()) return null
        val readCapability = when {
            RisingStonesCapability.AccountRead in sessionProvider.capabilities -> RisingStonesCapability.AccountRead
            canPerformAuthenticatedWrites -> RisingStonesCapability.RecruitmentWrite
            else -> return null
        }
        return try {
            val identity = perform(
                RisingStonesApiRequest("api/home/groupAndRole/getCharacterBindInfo", query = listOf(q("platform", 1))),
                required = true,
                readCapability = readCapability,
            ).obj("data") ?: return null
            if ((!canPerformAuthenticatedWrites && !canAttemptAuthenticatedWrites) ||
                identity.string("character_name").isNullOrBlank()
            ) return null
            val currentUuid = identity.string("uuid")?.takeIf(String::isNotBlank) ?: return null
            currentUuid == authorUuid
        } catch (error: CancellationException) {
            throw error
        } catch (error: DutyRecruitmentException.AuthenticationRequired) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchCommunityFilterCatalog(kind: CommunityRecruitmentKind): CommunityRecruitmentFilterCatalog {
        val areas = fetchAreas()
        return when (kind) {
            CommunityRecruitmentKind.Beginner -> CommunityRecruitmentFilterCatalog(
                styles = fetchOptions("api/home/recruit/styleConfigList", "style"), areas = areas,
            )
            CommunityRecruitmentKind.Guild -> CommunityRecruitmentFilterCatalog(
                guildLabels = fetchOptions("api/home/recruit/guildLabelList", "name"), areas = areas,
            )
            CommunityRecruitmentKind.Other -> CommunityRecruitmentFilterCatalog(
                categories = fetchOptions("api/home/recruit/categoryConfigList", "name"), areas = areas,
            )
            CommunityRecruitmentKind.RolePlay -> CommunityRecruitmentFilterCatalog(areas = areas)
        }
    }

    override suspend fun fetchRolePlayMembers(id: Int): List<RolePlayRecruitmentMember> =
        fetchRolePlayMemberPage(RolePlayMemberQuery(id)).items

    override suspend fun fetchRolePlayMemberPage(query: RolePlayMemberQuery): RolePlayMemberPage =
        rolePlayDirectory.members(query)

    override suspend fun fetchRolePlayMemberDetail(id: Int): RolePlayMemberDetail = rolePlayDirectory.member(id)

    override suspend fun fetchRolePlayActivities(recruitmentId: Int): List<RolePlayActivity> =
        rolePlayDirectory.activities(recruitmentId)

    override suspend fun fetchRolePlayActivityDetail(id: Int): RolePlayActivityDetail = rolePlayDirectory.activity(id)

    override suspend fun fetchRolePlayReviews(id: Int, page: Int, limit: Int): RolePlayRecruitmentReviewPage =
        fetchRolePlayReviews(RolePlayRecruitmentReviewQuery(id, page, limit, RolePlayRecruitmentReviewOrder.Hottest))

    override suspend fun fetchRolePlayReviews(query: RolePlayRecruitmentReviewQuery): RolePlayRecruitmentReviewPage =
        fetchRolePlayReviewsWithAuthors(query).page

    override suspend fun fetchRolePlayReviewsWithAuthors(query: RolePlayRecruitmentReviewQuery): RolePlayReviewAuthorPage {
        val normalizedLimit = query.limit.coerceAtLeast(1)
        val data = perform(
            RisingStonesApiRequest(
                "api/home/recruit/recruitRpCommentDetail",
                query = listOf(
                    q("id", query.recruitmentId), q("order", query.order.wireValue), q("page", query.page.coerceAtLeast(1)),
                    q("limit", normalizedLimit), q("tempsuid", temporarySessionId),
                ),
            ),
        ).obj("data") ?: throw DutyRecruitmentException.MissingPayload
        val rows = data.requiredArray("rows")
        val items = rows.mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val contentHtml = row.text("mask_content") ?: return@mapNotNull null
            RolePlayRecruitmentReview(
                id = row.text("id") ?: return@mapNotNull null,
                authorName = row.text("character_name") ?: return@mapNotNull null,
                avatarUrl = row.text("avatar"),
                location = location(row.text("area_name"), row.text("group_name")),
                content = contentHtml.plainText(),
                score = row.text("score"),
                likeCount = row.int("like_count") ?: 0,
                isLiked = row.int("is_like") == 1,
                createdAt = row.instant("created_at"),
                imageUrls = row.text("comment_pic").imageUrls(),
                childCount = row.int("children_count") ?: 0,
                contentHtml = contentHtml,
            )
        }
        return RolePlayReviewAuthorPage(
            RolePlayRecruitmentReviewPage(items, query.page.coerceAtLeast(1), rows.size >= normalizedLimit),
            authorIndex(rows, items.map { it.id }) { row ->
                row.text("id")?.takeIf { row.text("character_name") != null && row.text("mask_content") != null }
            },
        )
    }

    override suspend fun fetchRolePlaySubcomments(
        rootParentId: String,
        page: Int,
        limit: Int,
    ): RolePlayRecruitmentSubcommentPage = fetchRolePlaySubcommentsWithAuthors(rootParentId, page, limit).page

    override suspend fun fetchRolePlaySubcommentsWithAuthors(
        rootParentId: String,
        page: Int,
        limit: Int,
    ): RolePlaySubcommentAuthorPage {
        val normalizedLimit = limit.coerceAtLeast(1)
        val data = perform(
            RisingStonesApiRequest(
                "api/home/recruit/recruitRpSubCommentDetail",
                query = listOf(
                    q("root_parent", rootParentId), q("order", "earliest"), q("page", page.coerceAtLeast(1)),
                    q("limit", normalizedLimit), q("tempsuid", temporarySessionId),
                ),
            ),
        ).obj("data") ?: throw DutyRecruitmentException.MissingPayload
        val rows = data.requiredArray("rows")
        val items = rows.mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val contentHtml = row.text("mask_content") ?: return@mapNotNull null
            RolePlayRecruitmentSubcomment(
                id = row.text("id") ?: return@mapNotNull null,
                authorName = row.text("character_name") ?: return@mapNotNull null,
                avatarUrl = row.text("avatar"),
                replyTargetName = row.text("to_cname"),
                content = contentHtml.plainText(),
                imageUrls = row.text("comment_pic").imageUrls(),
                contentHtml = contentHtml,
            )
        }
        return RolePlaySubcommentAuthorPage(
            RolePlayRecruitmentSubcommentPage(items, page.coerceAtLeast(1), rows.size >= normalizedLimit),
            authorIndex(rows, items.map { it.id }) { row ->
                row.text("id")?.takeIf { row.text("character_name") != null && row.text("mask_content") != null }
            },
        )
    }

    override suspend fun fetchRolePlayRating(id: Int): RolePlayRecruitmentRating {
        val values = perform(
            RisingStonesApiRequest(
                "api/home/recruit/getRecruitRpScoreListByRpId",
                query = listOf(q("id", id), q("tempsuid", temporarySessionId)),
            ),
        ).requiredArray("data").take(5).map { (it as? JsonPrimitive)?.flexInt() ?: 0 }.toMutableList()
        while (values.size < 5) values += 0
        return RolePlayRecruitmentRating(values)
    }

    override suspend fun likeRolePlayReview(id: String): Int = writeForm(
        "api/home/recruit/rpCommentlike", listOf("id" to id, "type" to "2"),
    ) { envelope ->
        envelope.int("data")?.takeIf { it == -1 || it == 1 }
            ?: throw DutyRecruitmentException.MissingPayload
    }

    private suspend fun response(path: String, id: Int, contactInfo: String): String? = writeForm(
        path, listOf("id" to id.toString(), "contact_info" to contactInfo),
    ) { envelope ->
        val data = envelope.obj("data") ?: throw DutyRecruitmentException.MissingPayload
        data.string("recruit_contact_info", "recruitContactInfo")?.takeIf(String::isNotBlank)
    }

    private suspend fun fetchOptions(path: String, nameKey: String): List<CommunityRecruitmentFilterOption> =
        perform(RisingStonesApiRequest(path)).requiredArray("data").mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            CommunityRecruitmentFilterOption(
                item.text("id") ?: return@mapNotNull null,
                item.text(nameKey) ?: return@mapNotNull null,
            )
        }

    private suspend fun fetchAreas(): List<CommunityRecruitmentArea> = perform(
        RisingStonesApiRequest("api/home/groupAndRole/getAreaAndGroupList"),
    ).requiredArray("data").mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        CommunityRecruitmentArea(
            id = item.text("AreaID", "area_id", "areaId") ?: return@mapNotNull null,
            name = item.text("AreaName", "area_name", "areaName") ?: return@mapNotNull null,
            servers = item.requiredArray("vGroup", "groups").mapNotNull { groupValue ->
                val group = groupValue as? JsonObject ?: return@mapNotNull null
                CommunityRecruitmentServer(
                    group.text("GroupID", "group_id", "groupId") ?: return@mapNotNull null,
                    group.text("GroupName", "group_name", "groupName") ?: return@mapNotNull null,
                )
            },
        )
    }

    private suspend fun <T> writeForm(
        path: String,
        fields: List<Pair<String, String>>,
        map: (JsonObject) -> T,
    ): T {
        val all = fields + ("tempsuid" to temporarySessionId)
        val request = RisingStonesApiRequest(
            path = path,
            method = RisingStonesHttpMethod.Post,
            query = listOf(q("tempsuid", temporarySessionId)),
            body = all.joinToString("&") { (key, value) -> "${key.encoded()}=${value.encoded()}" }.encodeToByteArray(),
            contentType = "application/x-www-form-urlencoded; charset=utf-8",
        )
        val explicit = sessionProvider as? RisingStonesExplicitCapabilityProvider
        if (explicit == null) return map(perform(request, required = true, mutation = true))

        val context = RisingStonesRequestContext(
            path = path,
            requirement = RisingStonesAuthenticationRequirement.Required,
            capability = RisingStonesCapability.RecruitmentWrite,
        )
        val attempt = explicit.beginCapabilityAttempt(context)
            ?: throw DutyRecruitmentException.AuthenticationRequired
        try {
            val headers = attempt.authorizer.headers(
                path,
                RisingStonesCapability.RecruitmentWrite,
                RisingStonesAuthenticationRequirement.Required,
            )
            val response = client.execute(request.copy(headers = request.headers + headers))
            if (response.statusCode !in 200..299) {
                throw RisingStonesHttpException.ServerResponse(response.statusCode, response.body)
            }
            val envelope = try {
                json.parseToJsonElement(response.body.decodeToString()).jsonObject
            } catch (_: IllegalArgumentException) {
                throw DutyRecruitmentException.MissingPayload
            }
            val code = envelope.int("code") ?: 0
            if (envelope.isAuthenticationFailure()) {
                revalidateWithoutReplay()
                throw DutyRecruitmentException.AuthenticationRequired
            }
            if (!RisingStonesResponsePolicy.accepts(code)) {
                throw DutyRecruitmentException.Business(code, envelope.text("msg", "message"))
            }
            val result = map(envelope)
            if (!attempt.complete()) throw DutyRecruitmentException.AuthenticationRequired
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!error.isHttpIdentityConflict() && error.isHttpAuthenticationFailure()) {
                revalidateWithoutReplay()
                throw DutyRecruitmentException.AuthenticationRequired
            }
            throw error
        } finally {
            attempt.close()
        }
    }

    private suspend fun revalidateWithoutReplay() {
        try {
            sessionProvider.refreshAuthorizer()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // This read-only refresh is best effort; the rejected write remains the reported result.
        }
    }

    private suspend fun perform(
        request: RisingStonesApiRequest,
        required: Boolean = false,
        mutation: Boolean = false,
        readCapability: RisingStonesCapability? = null,
    ): JsonObject = try {
        performWithAuthentication(request, required, mutation, readCapability)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!error.isHttpIdentityConflict() && error.isHttpAuthenticationFailure()) {
            throw DutyRecruitmentException.AuthenticationRequired
        }
        throw error
    }

    private suspend fun performWithAuthentication(
        request: RisingStonesApiRequest,
        required: Boolean,
        mutation: Boolean,
        readCapability: RisingStonesCapability?,
    ): JsonObject {
        val capability = if (mutation || (!required && canPerformAuthenticatedWrites)) {
            RisingStonesCapability.RecruitmentWrite
        } else {
            readCapability ?: RisingStonesCapability.RecruitmentAuthenticated
        }
        val initial = if (capability in sessionProvider.capabilities) {
            sessionProvider.currentAuthorizer()
        } else {
            null
        }
        if (required && initial == null) throw DutyRecruitmentException.AuthenticationRequired
        suspend fun execute(authorizer: RisingStonesRequestAuthorizer?): JsonObject {
            val headers = authorizer?.headers(
                path = request.path,
                capability = capability,
                requirement = if (required) {
                    RisingStonesAuthenticationRequirement.Required
                } else {
                    RisingStonesAuthenticationRequirement.Optional
                },
            ).orEmpty()
            val response = client.execute(request.copy(headers = request.headers + headers))
            if (response.statusCode !in 200..299) {
                throw RisingStonesHttpException.ServerResponse(response.statusCode, response.body)
            }
            return json.parseToJsonElement(response.body.decodeToString()).jsonObject
        }
        if (mutation) {
            val response = try {
                execute(initial)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (initial != null && !error.isHttpIdentityConflict() && error.isHttpAuthenticationFailure()) {
                    revalidateWithoutReplay()
                }
                throw error
            }
            val code = response.int("code") ?: 0
            if (response.isAuthenticationFailure()) {
                revalidateWithoutReplay()
                throw DutyRecruitmentException.AuthenticationRequired
            }
            if (!RisingStonesResponsePolicy.accepts(code)) {
                throw DutyRecruitmentException.Business(code, response.text("msg", "message"))
            }
            return response
        }
        var recoveryAttempted = false
        var response = try {
            execute(initial)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            when {
                error.isHttpIdentityConflict() -> {
                    recoveryAttempted = true
                    execute((sessionProvider as? RisingStonesIdentityConflictResolver)
                        ?.awaitIdentityConflictResolution() ?: throw error)
                }
                initial != null && error.isHttpAuthenticationFailure() -> {
                    recoveryAttempted = true
                    execute(sessionProvider.refreshAuthorizer() ?: throw error)
                }
                else -> throw error
            }
        }
        if (!recoveryAttempted && response.int("code") == 10105) {
            response = execute(
                (sessionProvider as? RisingStonesIdentityConflictResolver)
                    ?.awaitIdentityConflictResolution()
                    ?: throw DutyRecruitmentException.Business(10105, response.text("msg")),
            )
        } else if (!recoveryAttempted && initial != null && response.isAuthenticationFailure()) {
            sessionProvider.refreshAuthorizer()?.let { response = execute(it) }
        }
        val code = response.int("code") ?: 0
        if (response.isAuthenticationFailure()) throw DutyRecruitmentException.AuthenticationRequired
        if (!RisingStonesResponsePolicy.accepts(code)) throw DutyRecruitmentException.Business(code, response.text("msg", "message"))
        return response
    }

    private fun dutySummary(value: JsonElement): DutyRecruitmentSummary? {
        val row = value as? JsonObject ?: return null
        val id = row.int("id") ?: return null
        val user = row.obj("user_info", "userInfo")
        return DutyRecruitmentSummary(
            id = id,
            uuid = row.text("uuid"),
            avatarUrl = row.text("avatar") ?: user?.text("avatar"),
            characterName = row.text("character_name", "characterName") ?: "—",
            areaName = row.text("area_name", "areaName") ?: "—",
            groupName = row.text("group_name", "groupName") ?: "—",
            targetAreaName = row.text("target_area_name", "targetAreaName")
                ?: row.text("area_name", "areaName") ?: "—",
            dutyType = row.text("fb_type", "fbType").orEmpty(),
            dutyName = row.text("fb_name", "fbName").orEmpty(),
            teamComposition = row.text("team_composition", "teamComposition").orEmpty(),
            progress = row.text("progress").orEmpty(),
            schedule = row.text("fb_time", "fbTime").orEmpty(),
            strategy = row.text("strategy").orEmpty(),
            beginTime = row.instant("begin_time", "beginTime"),
            endTime = row.instant("end_time", "endTime"),
            responseCount = row.int("response_num", "responseNum") ?: 0,
            status = row.int("status") ?: 0,
            labels = row.labels(),
            jobs = row.array("job_info", "jobInfo").mapNotNull { (it as? JsonObject)?.job() },
            requiredJobCodes = row.stringArray("need_job", "needJob"),
            roleCounts = row.roleCounts(),
            updatedAt = row.instant("sort_updated_time", "sortUpdatedTime", "updated_at", "updatedAt", "created_at", "createdAt"),
        )
    }
}

private suspend fun RisingStonesRequestAuthorizer.headers(
    path: String,
    capability: RisingStonesCapability,
    requirement: RisingStonesAuthenticationRequirement,
): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    authorize(
        context = RisingStonesRequestContext(
            path = path,
            requirement = requirement,
            capability = capability,
        ),
        sink = RisingStonesHeaderSink { name, value -> headers[name] = value },
    )
    return headers
}

private fun JsonObject.communitySummary(kind: CommunityRecruitmentKind): CommunityRecruitmentSummary? {
    val id = int("id") ?: return null
    return when (kind) {
        CommunityRecruitmentKind.Beginner -> {
            val author = text("character_name") ?: return null
            val card = BeginnerRecruitmentCard(
                publisherName = author,
                publisherServer = location(text("area_name"), text("group_name")),
                identity = when (int("identity")) { 1 -> BeginnerRecruitmentIdentity.Mentor; 2 -> BeginnerRecruitmentIdentity.Newcomer; else -> null },
                title = text("title") ?: "—",
                description = text("detail_mask")?.plainText(),
                styles = beginnerStyles(),
                targetServer = targetLocation(),
                weekdayTime = text("weekday_time"),
                weekendTime = text("weekend_time"),
                isResponded = int("is_response") == 1,
                recruiterContactInfo = null,
            )
            CommunityRecruitmentSummary(
                id, kind, card.title, author, text("avatar"), card.publisherServer, card.targetServer,
                card.description, null, activityCount = int("response_num"), updatedAt = instant("updated_at", "sort_updated_time", "created_at"),
                beginner = card,
            )
        }
        CommunityRecruitmentKind.Guild -> {
            val name = text("guild_name") ?: return null
            val labels = array("labelInfo").mapNotNull { (it as? JsonObject)?.text("name") } +
                text("custom_label").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            val card = GuildRecruitmentCard(
                name, text("guild_tag"), location(text("area_name"), text("group_name")),
                text("weekday_time"), text("weekend_time"), text("active_member_num"),
                text("target_recruit_num"), labels.distinct(), text("cover_pic"),
            )
            CommunityRecruitmentSummary(id, kind, name, text("character_name"), null, card.server, targetLocation(), null, card.coverUrl, guild = card)
        }
        CommunityRecruitmentKind.Other -> {
            val category = text("category_name") ?: return null
            val title = text("title") ?: return null
            val author = text("character_name") ?: return null
            val cover = text("cover_pic") ?: text("detail_pic").imageUrls().firstOrNull()
                ?: DefaultOtherRecruitmentCover
            val card = OtherRecruitmentCard(
                category, title, text("detail_mask")?.plainText(), author,
                location(text("area_name"), text("group_name")), text("avatar"), cover,
                targetLocation(), int("response_num"),
            )
            CommunityRecruitmentSummary(
                id, kind, title, author, card.publisherAvatarUrl, card.publisherServer, card.targetServer,
                card.description, cover, activityCount = card.responseCount, updatedAt = instant("updated_at", "sort_updated_time"), other = card,
            )
        }
        CommunityRecruitmentKind.RolePlay -> {
            val name = text("rp_name") ?: return null
            val card = RolePlayRecruitmentCard(
                name, rolePlayTypes(), text("profile"), text("open_time"),
                text("custom_label").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
                text("cover_pic"),
            )
            CommunityRecruitmentSummary(
                id, kind, name, text("character_name"), null,
                location(text("area_name"), text("group_name")),
                location(text("rp_area_name"), text("rp_group_name")), text("profile"), card.coverUrl,
                rolePlay = card,
            )
        }
    }
}

private fun JsonObject.communityDetailSummary(kind: CommunityRecruitmentKind): CommunityRecruitmentSummary? {
    val base = communitySummary(kind) ?: return null
    val user = obj("userInfo", "user_info")
    return base.copy(
        avatarUrl = base.avatarUrl ?: user?.text("avatar"),
        footerImageUrls = text("foot_pic").imageUrls(),
        activityCount = int("response_num") ?: base.activityCount,
        updatedAt = instant("updated_at") ?: base.updatedAt,
        beginner = base.beginner?.copy(recruiterContactInfo = text("contact_info_mask")),
        other = base.other?.copy(responseCount = int("response_num")),
    )
}

private fun JsonObject.beginnerStyles(): List<BeginnerRecruitmentStyle> = array("styleInfo").mapNotNull {
    val item = it as? JsonObject ?: return@mapNotNull null
    BeginnerRecruitmentStyle(item.text("style") ?: return@mapNotNull null, item.text("pic_url"))
}

private fun JsonObject.rolePlayTypes(): List<RolePlayRecruitmentType> = array("rp_type").mapNotNull {
    when ((it as? JsonPrimitive)?.contentOrNull) {
        "0" -> RolePlayRecruitmentType.None
        "1" -> RolePlayRecruitmentType.Light
        "2" -> RolePlayRecruitmentType.Medium
        "3" -> RolePlayRecruitmentType.Heavy
        else -> null
    }
}

private fun JsonObject.targetLocation(): String? = location(text("target_area_name"), text("target_group_name"))
private fun location(area: String?, group: String?): String? = listOfNotNull(area, group).filter(String::isNotBlank)
    .joinToString(" · ").takeIf(String::isNotBlank)

private fun JsonObject.labels(): List<DutyRecruitmentLabel> {
    val fallback = stringArray("label")
    val mapped = array("label_info", "labelInfo").mapIndexedNotNull { index, value ->
        val item = value as? JsonObject ?: return@mapIndexedNotNull null
        val name = item.text("name") ?: return@mapIndexedNotNull null
        DutyRecruitmentLabel(item.text("id") ?: fallback.getOrNull(index) ?: name, name, item.int("weight") ?: 0)
    }
    return (mapped.ifEmpty { fallback.map { DutyRecruitmentLabel(it, it, 0) } })
        .distinctBy { "${it.id}|${it.name}" }
}

private fun JsonObject.job(): DutyRecruitmentJob? {
    val name = text("value") ?: return null
    return DutyRecruitmentJob(
        id = text("id") ?: listOfNotNull(name, text("job_pic_url", "jobPicUrl"), text("job_type", "jobType")).joinToString("|"),
        name = name,
        iconUrl = text("job_pic_url", "jobPicUrl"),
        roleType = text("job_type", "jobType"),
    )
}

private fun JsonObject.roleCounts() = DutyRecruitmentRoleCounts(
    int("MT") ?: 0, int("ST") ?: 0, int("H1") ?: 0, int("H2") ?: 0,
    int("H") ?: 0, int("T") ?: 0, int("D1") ?: 0, int("D2") ?: 0,
    int("D3") ?: 0, int("D4") ?: 0,
)

private fun q(name: String, value: Any?) = RisingStonesApiQueryItem(name, value?.toString().orEmpty())
private fun JsonObject.element(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { this[it]?.takeUnless { value -> value is JsonNull } }
private fun JsonObject.text(vararg names: String): String? = (element(*names) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.string(vararg names: String): String? =
    (element(*names) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(vararg names: String): Int? = (element(*names) as? JsonPrimitive)?.flexInt()
private fun JsonPrimitive.flexInt(): Int? = intOrNull ?: contentOrNull?.toIntOrNull() ?: doubleOrNull?.toInt()
private fun JsonObject.obj(vararg names: String): JsonObject? = element(*names) as? JsonObject
private fun JsonObject.array(vararg names: String): List<JsonElement> = (element(*names) as? JsonArray).orEmpty()
private fun JsonObject.requiredArray(vararg names: String): List<JsonElement> =
    element(*names) as? JsonArray ?: throw DutyRecruitmentException.MissingPayload
private fun JsonObject.stringArray(vararg names: String): List<String> = array(*names).mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull
}
private fun JsonObject.instant(vararg names: String): Instant? = element(*names).toInstantOrNull()
private fun JsonElement?.toInstantOrNull(): Instant? {
    val primitive = this as? JsonPrimitive ?: return null
    val numeric = primitive.contentOrNull?.toLongOrNull()
    if (numeric != null) return if (numeric > 10_000_000_000L) Instant.ofEpochMilli(numeric) else Instant.ofEpochSecond(numeric)
    val text = primitive.contentOrNull ?: return null
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    runCatching { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .atZone(ZoneId.of("Asia/Shanghai")).toInstant() }
    return null
}
private fun String?.imageUrls(): List<String> = this.orEmpty().split(',').map(String::trim)
    .filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
private fun String.htmlImageUrls(): List<String> = HtmlImageRegex.findAll(this)
    .map { it.groupValues[1].trim() }
    .filter { it.startsWith("http://") || it.startsWith("https://") }
    .distinct()
    .toList()
private fun String.plainText(): String = replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\s+"), " ").trim()
private fun String.encoded() = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun JsonObject.isAuthenticationFailure(): Boolean {
    val code = int("code")
    if (RisingStonesResponsePolicy.accepts(code) || code == 10105) return false
    if (code in setOf(401, 403, 10003, 10004, 10005, 10403)) return true
    val message = text("msg", "message").orEmpty().lowercase()
    return listOf("未登录", "登录失效", "登录过期", "token失效", "unauthorized", "session expired")
        .any(message::contains)
}
private fun Throwable.isHttpAuthenticationFailure(): Boolean = causeChain().any {
    it is RisingStonesHttpException.ServerResponse && it.statusCode in setOf(401, 403)
}
private fun Throwable.isHttpIdentityConflict(): Boolean = causeChain().any { cause ->
    cause is RisingStonesHttpException.ServerResponse && runCatching {
        Json.parseToJsonElement(cause.responseBody.decodeToString()).jsonObject.int("code") == 10105
    }.getOrDefault(false)
}
private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

private val HtmlImageRegex = Regex(
    """<img[^>]+src=[\"']([^\"']+)[\"']""",
    RegexOption.IGNORE_CASE,
)
private const val DefaultOtherRecruitmentCover =
    "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/default_recruit_cover.jpg"

private fun JsonObject.communityAuthorUuid(): String? =
    (this["uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun <K> authorIndex(rows: List<JsonElement>, validIds: List<K>, id: (JsonObject) -> K?): Map<K, String> {
    val valid = validIds.toSet()
    val seen = hashSetOf<K>()
    return buildMap {
        rows.forEach { value ->
            val row = value as? JsonObject ?: return@forEach
            val key = id(row)?.takeIf { it in valid } ?: return@forEach
            if (seen.add(key)) row.communityAuthorUuid()?.let { put(key, it) }
        }
    }
}
