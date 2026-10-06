package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.glamour.data.GlamourApiService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowsePage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowseRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourBrowsingService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourCollectionService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetailTag
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFolderNameMaximumLength
import top.cxmeow.risingstones.feature.glamour.domain.GlamourJob
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListPage
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListRequest
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTag
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTagCategory
import top.cxmeow.risingstones.feature.glamour.domain.GlamourTribe
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

suspend fun readPublishedGlamourBrowsePage(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): GlamourBrowsePage {
    val service: GlamourBrowsingService = GlamourApiService(client, session)
    val categories: List<GlamourTagCategory> = service.fetchTagCategories()
    val tag: GlamourTag? = categories.firstOrNull()?.tags?.firstOrNull()
    val tribes: List<GlamourTribe> = service.fetchTribes()
    val tribe = tribes.firstOrNull()
    val request = GlamourBrowseRequest(
        listing = GlamourListRequest(filter = GlamourFilter(raceId = tribe?.raceId)),
        tagIds = tag?.let { setOf(it.id) }.orEmpty(),
        tribeId = tribe?.id,
    )
    val firstPage: GlamourBrowsePage = service.fetchBrowsePage(request)
    return service.fetchBrowsePage(
        request.copy(
            listing = request.listing.copy(page = firstPage.page.currentPage + 1),
            pageTime = firstPage.nextPageTime,
        ),
    )
}

suspend fun readPublishedGlamourFollowing(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): GlamourBrowsePage = GlamourApiService(client, session).fetchBrowsePage(
    GlamourBrowseRequest(following = true),
)

suspend fun readPublishedLegacyGlamours(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): GlamourListPage {
    val service: GlamourService = GlamourApiService(client, session)
    return service.fetchGlamours(GlamourListRequest())
}

/** This fixture compiles against published AARs, including regenerated constructor/copy signatures. */
suspend fun readPublishedGlamourDetailMetadata(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
    listingId: Int,
): GlamourDetail {
    val detail = GlamourApiService(client, session).fetchDetail(listingId)
    val jobs: List<GlamourJob> = detail.jobs.map { GlamourJob(it.id, it.name) }
    val genders: List<Int> = detail.genderIds
    val tags: List<GlamourDetailTag> = detail.tags.map { tag ->
        val categoryCode: String? = tag.categoryCode
        tag.copy(categoryCode = categoryCode)
    }
    return detail.copy(jobs = jobs, genderIds = genders, tags = tags)
}

suspend fun usePublishedGlamourCollections(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
    listingId: Int,
    folderId: Int,
    folderName: String,
) {
    val service: GlamourCollectionService = GlamourApiService(client, session)
    require(folderName.trim().length in 1..GlamourFolderNameMaximumLength)
    service.updateFavoriteFolder(folderId, folderName, isPublic = false)
    service.favoriteInFolder(listingId, folderId)
}
