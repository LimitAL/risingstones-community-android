package top.cxmeow.risingstones.integration.mavenconsumer

import kotlinx.serialization.json.Json
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.data.GuildApiService
import top.cxmeow.risingstones.feature.guild.data.RisingStonesGuildSessionValidator
import top.cxmeow.risingstones.feature.guild.domain.*
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

fun publishedGuildService(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): GuildService =
    GuildApiService(client, session, Json { ignoreUnknownKeys = true })

fun publishedGuildValidator(base: RisingStonesSessionValidator, client: RisingStonesPublicApiClient): RisingStonesSessionValidator =
    RisingStonesGuildSessionValidator(base, client, Json { ignoreUnknownKeys = true })

suspend fun readPublishedOwnGuild(service: GuildService): GuildInfo? = when (val own = service.ownGuild()) {
    OwnGuild.None -> null
    is OwnGuild.Joined -> service.info(own.guildId)
}

suspend fun readPublishedGuildMembers(service: GuildService, id: GuildId): GuildMembers = service.members(id)

suspend fun readPublishedGuildActivity(service: GuildService, id: GuildId): GuildPage<GuildActivitySummary> =
    service.activities(id, page = 1)

suspend fun readPublishedGuildPhotos(service: GuildService, id: GuildId): GuildPage<GuildPhotoSummary> =
    service.photos(id, page = 1)

suspend fun readPublishedGuildPhotoWithComments(service: GuildService, photoId: Int): Pair<GuildPhotoDetail, GuildPage<GuildPhotoComment>> {
    val detail = service.photo(photoId)
    val first = service.comments(photoId)
    val page = if (first.hasMore) service.comments(photoId, first.page + 1, first.nextPageTime) else first
    page.items.firstOrNull { it.childCount > 0 }?.let { service.replies(it.id, page = 1) }
    return detail to page
}

fun publishedGuildActions(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): GuildActionService =
    GuildApiService(client, session)

fun publishedGuildImages(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): GuildImageUploadService =
    top.cxmeow.risingstones.feature.guild.data.GuildImageUploadApiService(client, session)

suspend fun usePublishedGuildOperations(actions: GuildActionService, images: GuildImageUploadService, id: GuildId) {
    actions.beginActionScope().use { scope ->
        if (scope.isCurrent()) {
            actions.guildEligibility(scope, id)
            actions.photoEligibility(scope, 7)
            actions.commentEligibility(scope, 31)
            actions.labels(scope)
            actions.updateGuildInfo(scope, id, GuildInfoUpdate.Description("fixture"))
            actions.updateGuildInfo(scope, id, GuildInfoUpdate.HousingVisibility(true))
            actions.updateGuildInfo(scope, id, GuildInfoUpdate.Labels(listOf(GuildLabelId("1"))))
            actions.updateGuildInfo(scope, id, GuildInfoUpdate.WeekdayActiveTime(GuildActiveTimeRange(0, 24)))
            actions.updateGuildInfo(scope, id, GuildInfoUpdate.WeekendActiveTime(GuildActiveTimeRange(10, 20)))
            actions.togglePhotoLike(scope, 7)
            actions.commentPhoto(scope, GuildPhotoCommentDraft(7, "fixture",
                mentions = listOf(GuildPhotoCommentMention("fixture", "Fixture"))))
            actions.deleteOwnComment(scope, 31)
            val image = images.uploadImage(scope, GuildImagePurpose.Album,
                GuildImageUploadInput(byteArrayOf(1), "image/png"))
            actions.registerAlbumPhotos(scope, id, listOf(image))
            actions.deletePhoto(scope, 7)
        }
    }
}
