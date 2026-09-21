package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.recruitment.data.DutyRecruitmentApiService
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

suspend fun readPublishedRecruitmentFilters(client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider): DutyRecruitmentListPage {
    val service: RecruitmentBrowsingService = DutyRecruitmentApiService(client, session)
    val catalog: DutyRecruitmentFilterCatalog = service.fetchDutyFilterCatalog()
    val type = catalog.catalogs.dutyTypes.firstOrNull().orEmpty()
    val composition = dutyRecruitmentTeamCompositionForType(type)
    return service.fetchDutyRecruitments(DutyRecruitmentBrowseQuery(
        list = DutyRecruitmentListQuery(dutyType = type),
        positions = DutyRecruitmentPosition.optionsForTeamComposition(composition).take(2),
        teamComposition = composition,
        labelIds = catalog.labels.take(1).map { it.id },
    ))
}

suspend fun readPublishedRecruitmentEligibility(client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider, id: Int): DutyRecruitmentInteractionDetail {
    val service: RecruitmentResponseEligibilityService = DutyRecruitmentApiService(client, session)
    return service.fetchDutyInteractionDetail(id)
}

suspend fun readPublishedRecruitmentReviews(service: RecruitmentBrowsingService,
    id: Int): RolePlayRecruitmentReviewPage = service.fetchRolePlayReviews(
    RolePlayRecruitmentReviewQuery(id, order = RolePlayRecruitmentReviewOrder.ScoreDescending),
)

suspend fun readPublishedRolePlayDirectory(client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider, id: Int): RolePlayMemberPage {
    val service: RolePlayDirectoryService = DutyRecruitmentApiService(client, session)
    val first = service.fetchRolePlayMemberPage(RolePlayMemberQuery(id))
    first.items.firstOrNull()?.let { service.fetchRolePlayMemberDetail(it.id) }
    service.fetchRolePlayActivities(id).firstOrNull()?.let {
        val detail: RolePlayActivityDetail = service.fetchRolePlayActivityDetail(it.id)
        val blocks: List<RolePlayActivityBodyBlock> = detail.bodyBlocks
        blocks.filterIsInstance<RolePlayActivityBodyBlock.Html>().map { it.contentHtml }
    }
    return if (first.hasMore) service.fetchRolePlayMemberPage(RolePlayMemberQuery(id, 2, pageTime = first.pageTime)) else first
}

suspend fun readPublishedRecruitmentAuthors(service: RecruitmentAuthorService,
    query: CommunityRecruitmentQuery): Map<Int, String> = service.fetchCommunityRecruitmentsWithAuthors(query).authorUuids

fun publishedRecruitmentValidator(
    base: top.cxmeow.risingstones.network.RisingStonesSessionValidator,
    client: RisingStonesPublicApiClient,
): top.cxmeow.risingstones.network.RisingStonesSessionValidator =
    top.cxmeow.risingstones.feature.recruitment.data.RisingStonesRecruitmentSessionValidator(base, client)

fun publishedRecruitmentActionEligibility(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): Boolean {
    val service: RecruitmentActionEligibilityService = DutyRecruitmentApiService(client, session)
    return service.canAttemptAuthenticatedWrites
}
