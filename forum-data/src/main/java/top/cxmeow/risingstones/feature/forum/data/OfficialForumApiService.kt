package top.cxmeow.risingstones.feature.forum.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttempt
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictHandler
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumActionEligibilityService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumInteractionService
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumAuthor
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsingService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowseQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsePage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCategory
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumFeedFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumComment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentAuthoringService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentImageUpload
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentMention
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumMentionCandidate
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumException
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumListQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumIdentityConflictState
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPart
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPartFilter
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostDetail
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostInteraction
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostSummary
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVote
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVoteOption
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSubCommentQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteDraft
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumVoteResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val EmptyRisingStonesIdentityConflictState =
    MutableStateFlow(OfficialForumIdentityConflictState())

private data class ForumWriteAuthorization(
    val authorizer: RisingStonesRequestAuthorizer,
    val attempt: RisingStonesCapabilityAttempt?,
)

class OfficialForumApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider? = null,
    private val identityConflictHandler: OfficialForumIdentityConflictHandler? =
        sessionProvider as? OfficialForumIdentityConflictHandler,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : OfficialForumBrowsingService, OfficialForumInteractionService, OfficialForumCommentAuthoringService,
    OfficialForumActionEligibilityService {
    override val canPerformAuthenticatedWrites: Boolean
        get() = RisingStonesCapability.ForumWrite in sessionProvider?.capabilities.orEmpty()
    override val canUploadCommentImages: Boolean
        get() = RisingStonesCapability.ForumImageUpload in sessionProvider?.capabilities.orEmpty()
    override val canAttemptAuthenticatedWrites: Boolean
        get() = canPerformAuthenticatedWrites ||
            (sessionProvider as? RisingStonesExplicitCapabilityProvider)
                ?.canAttemptCapability(RisingStonesCapability.ForumWrite) == true
    override val canAttemptCommentImageUpload: Boolean
        get() = canUploadCommentImages ||
            (sessionProvider as? RisingStonesExplicitCapabilityProvider)
                ?.canAttemptCapability(RisingStonesCapability.ForumImageUpload) == true
    override val canReadMentionCandidates: Boolean
        get() = RisingStonesCapability.AccountRead in sessionProvider?.capabilities.orEmpty()
    override val identityConflictState: StateFlow<OfficialForumIdentityConflictState>
        get() = identityConflictHandler?.identityConflictState
            ?: EmptyRisingStonesIdentityConflictState

    override suspend fun reclaimIdentityConflict() {
        identityConflictHandler?.reclaimIdentityConflict()
    }

    override suspend fun cancelIdentityConflict() {
        identityConflictHandler?.cancelIdentityConflict()
    }

    override suspend fun fetchParts(): List<OfficialForumPartFilter> =
        fetchCategories(OfficialForumContentKind.Post).map { it.part }

    override suspend fun fetchCategories(kind: OfficialForumContentKind): List<OfficialForumCategory> {
        val response = get<PartListResponse>(
            "api/home/posts/partList",
            listOf(query("type", kind.wireValue)),
        ).verified()
        return (response.data ?: throw OfficialForumException.MissingPayload).categories()
    }

    override suspend fun fetchPosts(
        query: OfficialForumListQuery,
    ): OfficialForumPage<OfficialForumPostSummary> = fetchBrowsePage(OfficialForumBrowseQuery(query)).page

    override suspend fun fetchBrowsePage(query: OfficialForumBrowseQuery): OfficialForumBrowsePage {
        val page = query.list.page.coerceAtLeast(1)
        val limit = query.list.limit.coerceAtLeast(1)
        val response = get<PostListResponse>(
            "api/home/posts/postsList",
            listOf(
                query("type", query.list.contentKind.wireValue),
                query("is_top", if (query.filter == OfficialForumFeedFilter.Pinned) 1 else 0),
                query("is_refine", if (query.filter == OfficialForumFeedFilter.Refined) 1 else 0),
                query("part_id", query.list.partIds.joinToString(",")),
                query("hotType", ""),
                query("order", if (query.filter == OfficialForumFeedFilter.Latest) "latest" else ""),
                query("page", page),
                query("limit", limit),
            ) + listOfNotNull(query.pageTime?.takeIf { page > 1 && it.isNotBlank() }
                ?.let { query("pageTime", it) }),
        ).verified()
        val payload = response.data ?: throw OfficialForumException.MissingPayload
        return OfficialForumBrowsePage(payload.page(page, limit), payload.pageTime.stringValue)
    }

    override suspend fun searchPosts(
        query: OfficialForumSearchQuery,
    ): OfficialForumPage<OfficialForumPostSummary> = searchBrowsePage(query).page

    override suspend fun searchBrowsePage(
        query: OfficialForumSearchQuery,
        pageTime: String?,
    ): OfficialForumBrowsePage {
        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceAtLeast(1)
        val response = get<PostListResponse>(
            "api/common/search",
            listOf(
                query("type", query.contentKind.wireValue),
                query("keywords", query.keywords),
                query("part_id", query.partIds.joinToString(",")),
                query("orderBy", query.order.wireValue),
                query("page", page),
                query("limit", limit),
                query("pageTime", pageTime?.takeIf { page > 1 }.orEmpty()),
                query("tempsuid", temporarySessionId),
            ),
        ).verified()
        val payload = response.data ?: throw OfficialForumException.MissingPayload
        return OfficialForumBrowsePage(payload.page(page, limit), payload.pageTime.stringValue)
    }

    override suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail = fetchPostInteraction(id).detail

    override suspend fun fetchPostInteraction(id: Int): OfficialForumPostInteraction {
        val response = get<PostDetailResponse>(
            "api/home/posts/postsDetail",
            listOf(query("id", id)),
        ).verified()
        val payload = response.data ?: throw OfficialForumException.MissingPayload
        val detail = json.decodeFromJsonElement<PostDetailDto>(payload).domain()
            ?: throw OfficialForumException.MissingPayload
        val rawVotes = payload["voteInfo"] as? JsonArray ?: payload["vote_info"] as? JsonArray
        // The website checks presence across the raw list, not the count or participation.
        val resultsAvailable = rawVotes?.all { option ->
            (option as? JsonObject)?.containsKey("total_vote_num") == true
        } == true
        return OfficialForumPostInteraction(
            detail,
            if (resultsAvailable) detail.votes.mapTo(mutableSetOf()) { it.id } else emptySet(),
        )
    }

    override suspend fun fetchComments(
        query: OfficialForumCommentQuery,
    ): OfficialForumPage<OfficialForumComment> {
        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceAtLeast(1)
        val response = get<CommentListResponse>(
            "api/home/posts/postsCommentDetail",
            listOf(
                query("id", query.postId),
                query("order", query.order.wireValue),
                query("onlyLandlord", if (query.onlyPostAuthor) 1 else 0),
                query("page", page),
                query("limit", limit),
            ),
        ).verified()
        return response.data.page(page, limit)
    }

    override suspend fun fetchSubComments(
        query: OfficialForumSubCommentQuery,
    ): OfficialForumPage<OfficialForumComment> {
        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceAtLeast(1)
        val response = get<CommentListResponse>(
            "api/home/posts/postsSubCommentDetail",
            listOf(
                query("root_parent", query.rootParentId),
                query("order", query.order.wireValue),
                query("page", page),
                query("limit", limit),
                query("tempsuid", temporarySessionId),
            ),
        ).verified()
        return response.data.page(page, limit)
    }

    override suspend fun likePost(id: Int): Int = performLike(id, targetType = "1")

    override suspend fun likeComment(id: Int): Int = performLike(id, targetType = "2")

    private suspend fun performLike(id: Int, targetType: String): Int = formPost<MutationResponse, Int>(
        path = "api/home/posts/like",
        fields = listOf("id" to id.toString(), "type" to targetType, "tempsuid" to temporarySessionId),
    ) { response -> response.data.intValue?.takeIf { it == 1 || it == -1 }
        ?: throw OfficialForumException.MissingPayload }

    override suspend fun starPost(id: Int): Int = formPost<MutationResponse, Int>(
        path = "api/home/posts/star",
        fields = listOf("posts_id" to id.toString(), "tempsuid" to temporarySessionId),
    ) { response -> response.data.intValue?.takeIf { it == 1 || it == -1 }
        ?: throw OfficialForumException.MissingPayload }

    override suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload): String {
        if (image.bytes.isEmpty() || image.bytes.size > MaximumCommentImageBytes) {
            throw OfficialForumException.ImageUploadFailed
        }
        val filename = RisingStonesCos.objectName(image.mimeType)
        val path = "api/common/getCOSTokenI"
        val authorization = writeAuthorization(path, RisingStonesCapability.ForumImageUpload)
        try {
            val tokenResponse = try {
                client.execute(
                    RisingStonesApiRequest(
                        path,
                        query = listOf(query("channel", "default"), query("tempsuid", temporarySessionId)),
                        headers = authorization.authorizer.headers(
                            path,
                            RisingStonesAuthenticationRequirement.Required,
                            RisingStonesCapability.ForumImageUpload,
                        ),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.isHttpAuthenticationFailure()) refreshAfterWriteAuthenticationFailure()
                throw OfficialForumException.ImageUploadFailed
            }
            if (tokenResponse.statusCode !in 200..299) {
                if (tokenResponse.statusCode in setOf(401, 403)) refreshAfterWriteAuthenticationFailure()
                throw OfficialForumException.ImageUploadFailed
            }
            val token = try {
                json.decodeFromString<CosTokenResponse>(tokenResponse.body.decodeToString())
            } catch (_: SerializationException) {
                throw OfficialForumException.ImageUploadFailed
            }
            if (!RisingStonesResponsePolicy.accepts(token.code)) {
                if (token.isAuthenticationFailure()) refreshAfterWriteAuthenticationFailure()
                throw OfficialForumException.Business(token.code, token.message)
            }
            val payload = token.data ?: throw OfficialForumException.ImageUploadFailed
            val now = Instant.now().epochSecond
            if (payload.startTime <= 0 || payload.startTime > now || now >= payload.expiredTime ||
                payload.startTime >= payload.expiredTime || !safeObjectDirectory(payload.keyDir) ||
                listOf(payload.credentials.tmpSecretId, payload.credentials.tmpSecretKey,
                    payload.credentials.sessionToken).any { it.isBlank() || it.any(Char::isISOControl) }
            ) throw OfficialForumException.ImageUploadFailed
            val objectKey = "${payload.keyDir}/$filename"
            val uploadUrl = "https://ff14risingstones.gcloud.com.cn/$objectKey"
            val cosAuthorization = RisingStonesCos.authorization(
                url = uploadUrl,
                contentLength = image.bytes.size,
                secretId = payload.credentials.tmpSecretId,
                secretKey = payload.credentials.tmpSecretKey,
                startTime = payload.startTime,
                expiredTime = payload.expiredTime,
            )
            currentCoroutineContext().ensureActive()
            if (authorization.attempt != null) {
                val guard = authorization.attempt as? RisingStonesCapabilityAttemptGuard
                    ?: throw OfficialForumException.AuthenticationRequired
                if (!guard.isCurrent()) throw OfficialForumException.AuthenticationRequired
            } else if (!canUploadCommentImages) {
                throw OfficialForumException.AuthenticationRequired
            }
            val upload = try {
                client.executeAbsolute(
                    RisingStonesHttpRequest(
                        url = uploadUrl,
                        method = RisingStonesHttpMethod.Put,
                        headers = mapOf(
                            "Content-Type" to image.mimeType,
                            "Content-Length" to image.bytes.size.toString(),
                            "Authorization" to cosAuthorization,
                            "x-cos-security-token" to payload.credentials.sessionToken,
                        ),
                        body = image.bytes,
                        contentType = image.mimeType,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.isHttpAuthenticationFailure()) refreshAfterWriteAuthenticationFailure()
                throw OfficialForumException.ImageUploadFailed
            }
            if (upload.statusCode in setOf(401, 403)) refreshAfterWriteAuthenticationFailure()
            if (upload.statusCode !in 200..299) throw OfficialForumException.ImageUploadFailed
            if (authorization.attempt?.complete() == false) throw OfficialForumException.AuthenticationRequired
            return uploadUrl
        } finally {
            authorization.attempt?.close()
        }
    }

    override suspend fun fetchMentionCandidates(): List<OfficialForumMentionCandidate> {
        val payload = readMentionCandidates().verified().data as? JsonObject
            ?: throw OfficialForumException.MissingPayload
        val rows = payload["rows"] as? JsonArray ?: throw OfficialForumException.MissingPayload
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val uuid = row.mentionString("uuid")?.takeIf(String::isCommentMentionIdentity) ?: return@mapNotNull null
            val name = row.mentionString("character_name")?.takeIf(String::isCommentMentionIdentity) ?: return@mapNotNull null
            OfficialForumMentionCandidate(
                uuid = uuid,
                name = name,
                avatarUrl = row.mentionString("avatar").remoteUrl(),
                areaName = row.mentionString("area_name").orEmpty(),
                groupName = row.mentionString("group_name").orEmpty(),
            )
        }
    }

    override suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int> =
        submitCommentWithMentions(draft, emptyList())

    override suspend fun submitCommentWithMentions(
        draft: OfficialForumCommentDraft,
        mentions: List<OfficialForumCommentMention>,
    ): List<Int> {
        require(mentions.all { it.uuid.isCommentMentionIdentity() && it.name.isCommentMentionIdentity() }) {
            "Invalid official comment mention identity"
        }
        return formPost<CommentSubmitResponse, List<Int>>(
            path = "api/home/posts/comment",
            fields = listOf(
                "content" to draft.contentHtml,
                "posts_id" to draft.postId.toString(),
                "parent_id" to draft.parentId.toString(),
                "root_parent" to draft.rootParentId.toString(),
                "comment_pic" to draft.commentPictureText,
                "tempsuid" to temporarySessionId,
            ) + mentions.distinct().flatMapIndexed { index, mention ->
                listOf(
                    "atInfo[$index][uuid]" to mention.uuid,
                    "atInfo[$index][character_name]" to mention.name,
                )
            },
        ) { response ->
            response.data?.takeIf { ids -> ids.isNotEmpty() && ids.all { it > 0 } }
                ?: throw OfficialForumException.MissingPayload
        }
    }

    override suspend fun deleteComment(id: Int): Unit =
        formDelete<MutationResponse, Unit>(
            path = "api/home/posts/deleteComment",
            body = "comment_id=$id".encodeToByteArray(),
        ) { }

    override suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult {
        val options = draft.options.map { VoteSubmitOption(it.title, it.optionId) }
        return formPost<VoteSubmitResponse, OfficialForumVoteResult>(
            path = "api/home/posts/vote",
            fields = listOf(
                "posts_id" to draft.postId.toString(),
                "options" to json.encodeToString(options),
                "tempsuid" to temporarySessionId,
            ),
        ) { response ->
            val payload = response.data ?: throw OfficialForumException.MissingPayload
            val total = (payload["voteTotalUser"] ?: payload["vote_total_user"])
                .intValue?.takeIf { it >= 0 }
                ?: throw OfficialForumException.MissingPayload
            val rawDetails = (payload["voteDetails"] ?: payload["vote_details"]) as? JsonArray
                ?: throw OfficialForumException.MissingPayload
            val details = rawDetails.map { element ->
                val detail = element as? JsonObject ?: throw OfficialForumException.MissingPayload
                val optionId = detail["option_id"].intValue?.takeIf { it > 0 }
                    ?: throw OfficialForumException.MissingPayload
                val rawCount = detail["total_vote_num"]
                val count = if (rawCount == null || rawCount is JsonNull) 0 else {
                    rawCount.intValue?.takeIf { it >= 0 } ?: throw OfficialForumException.MissingPayload
                }
                optionId to count
            }.toMap()
            OfficialForumVoteResult(total, details)
        }
    }

    private suspend fun requireMentionReadCapability() {
        currentCoroutineContext().ensureActive()
        if (!canReadMentionCandidates) throw OfficialForumException.AuthenticationRequired
    }

    private suspend fun recoverMentionAuthorizer(
        identityConflict: Boolean,
        attempt: Int,
    ): RisingStonesRequestAuthorizer {
        requireMentionReadCapability()
        if (attempt != 0) {
            if (identityConflict) throw OfficialForumException.IdentityConflict
            throw OfficialForumException.AuthenticationRequired
        }
        val authorizer = if (identityConflict) {
            identityConflictHandler?.awaitIdentityConflictResolution()
        } else {
            sessionProvider?.refreshAuthorizer()
        }
        requireMentionReadCapability()
        return authorizer ?: if (identityConflict) throw OfficialForumException.IdentityConflict
        else throw OfficialForumException.AuthenticationRequired
    }

    private suspend fun readMentionCandidates(): MutationResponse = normalizeFinalAuthenticationFailure {
        val path = "api/home/userRelation/followList"
        requireMentionReadCapability()
        var authorizer = sessionProvider?.currentAuthorizer()
        requireMentionReadCapability()
        for (attempt in 0..1) {
            val headers = authorizer.headers(
                path,
                RisingStonesAuthenticationRequirement.Required,
                RisingStonesCapability.AccountRead,
            )
            requireMentionReadCapability()
            if (headers.isEmpty()) throw OfficialForumException.AuthenticationRequired
            val response = try {
                client.execute(
                    RisingStonesApiRequest(
                        path,
                        query = listOf(query("page", 1), query("limit", 5000)),
                        headers = headers,
                    ),
                ).also {
                    if (it.statusCode !in 200..299) {
                        throw RisingStonesHttpException.ServerResponse(it.statusCode, it.body)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                requireMentionReadCapability()
                val identityConflict = error.isHttpIdentityConflict()
                if (!identityConflict && !error.isHttpAuthenticationFailure()) throw error
                authorizer = recoverMentionAuthorizer(identityConflict, attempt)
                continue
            }
            requireMentionReadCapability()
            val decoded = try {
                json.decodeFromString<MutationResponse>(response.body.decodeToString())
            } catch (_: SerializationException) {
                throw OfficialForumException.MissingPayload
            }
            if (!decoded.isIdentityConflict() && !decoded.isAuthenticationFailure()) return decoded.verified()
            authorizer = recoverMentionAuthorizer(decoded.isIdentityConflict(), attempt)
        }
        throw OfficialForumException.AuthenticationRequired
    }

    private suspend inline fun <reified T : ApiResponse> get(
        path: String,
        query: List<RisingStonesApiQueryItem>,
    ): T = normalizeFinalAuthenticationFailure { getWithAuthentication<T>(path, query) }

    private suspend inline fun <reified T : ApiResponse> getWithAuthentication(
        path: String,
        query: List<RisingStonesApiQueryItem>,
    ): T {
        val initialAuthorizer = sessionProvider?.currentAuthorizer()
        val initialHeaders = initialAuthorizer.headers(
            path = path,
            requirement = RisingStonesAuthenticationRequirement.Optional,
        )
        val initial = try {
            client.execute(RisingStonesApiRequest(path, query = query, headers = initialHeaders))
        } catch (error: Exception) {
            if (initialHeaders.isNotEmpty() && error.isHttpIdentityConflict()) {
                val reclaimed = identityConflictHandler?.awaitIdentityConflictResolution()
                    .headers(path, RisingStonesAuthenticationRequirement.Optional)
                    .takeIf(Map<String, String>::isNotEmpty)
                    ?: throw error
                return json.decodeFromString(
                    client.execute(RisingStonesApiRequest(path, query = query, headers = reclaimed))
                        .body.decodeToString(),
                )
            }
            if (initialHeaders.isEmpty() || !error.isHttpAuthenticationFailure()) throw error
            val refreshed = sessionProvider?.refreshAuthorizer()
                .headers(path, RisingStonesAuthenticationRequirement.Optional)
                .takeIf(Map<String, String>::isNotEmpty)
                ?: throw error
            client.execute(RisingStonesApiRequest(path, query = query, headers = refreshed))
        }
        var decoded = json.decodeFromString<T>(initial.body.decodeToString())
        if (initialHeaders.isNotEmpty() && decoded.isIdentityConflict()) {
            identityConflictHandler?.awaitIdentityConflictResolution()
                .headers(path, RisingStonesAuthenticationRequirement.Optional)
                .takeIf(Map<String, String>::isNotEmpty)
                ?.let { reclaimed ->
                decoded = json.decodeFromString(
                    client.execute(RisingStonesApiRequest(path, query = query, headers = reclaimed))
                        .body.decodeToString(),
                )
            }
        } else if (initialHeaders.isNotEmpty() && decoded.isAuthenticationFailure()) {
            sessionProvider?.refreshAuthorizer()
                .headers(path, RisingStonesAuthenticationRequirement.Optional)
                .takeIf(Map<String, String>::isNotEmpty)
                ?.let { refreshed ->
                decoded = json.decodeFromString(
                    client.execute(RisingStonesApiRequest(path, query = query, headers = refreshed))
                        .body.decodeToString(),
                )
            }
        }
        return decoded
    }

    private suspend inline fun <reified T : ApiResponse, R> formPost(
        path: String,
        fields: List<Pair<String, String>>,
        crossinline map: (T) -> R,
    ): R {
        val body = fields.joinToString("&") { (name, value) ->
            "${name.formEncoded()}=${value.formEncoded()}"
        }.encodeToByteArray()
        return authenticatedWrite(path, RisingStonesCapability.ForumWrite, map) { headers ->
            formRequest(path, body, headers)
        }
    }

    private fun formRequest(
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
    ) = RisingStonesApiRequest(
        path = path,
        method = RisingStonesHttpMethod.Post,
        query = listOf(query("tempsuid", temporarySessionId)),
        headers = headers,
        body = body,
        contentType = "application/x-www-form-urlencoded; charset=utf-8",
    )

    private suspend inline fun <reified T : ApiResponse, R> formDelete(
        path: String,
        body: ByteArray,
        crossinline map: (T) -> R,
    ): R = authenticatedWrite(path, RisingStonesCapability.ForumWrite, map) { headers ->
        formDeleteRequest(path, body, headers)
    }

    private suspend inline fun <reified T : ApiResponse, R> authenticatedWrite(
        path: String,
        capability: RisingStonesCapability,
        crossinline map: (T) -> R,
        request: (Map<String, String>) -> RisingStonesApiRequest,
    ): R {
        val authorization = writeAuthorization(path, capability)
        try {
            val headers = authorization.authorizer.headers(
                path,
                RisingStonesAuthenticationRequirement.Required,
                capability,
            ).takeIf(Map<String, String>::isNotEmpty)
                ?: throw OfficialForumException.AuthenticationRequired
            val response = try {
                client.execute(request(headers))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.isHttpIdentityConflict()) throw OfficialForumException.IdentityConflict
                if (error.isHttpAuthenticationFailure()) refreshAfterWriteAuthenticationFailure()
                throw error
            }
            if (response.statusCode !in 200..299) {
                if (response.statusCode in setOf(401, 403)) refreshAfterWriteAuthenticationFailure()
                throw RisingStonesHttpException.ServerResponse(response.statusCode, response.body)
            }
            val decoded = try {
                json.decodeFromString<T>(response.body.decodeToString())
            } catch (_: SerializationException) {
                throw OfficialForumException.MissingPayload
            }
            if (decoded.isAuthenticationFailure()) refreshAfterWriteAuthenticationFailure()
            decoded.verified()
            val result = map(decoded)
            if (authorization.attempt?.complete() == false) {
                throw OfficialForumException.AuthenticationRequired
            }
            return result
        } finally {
            authorization.attempt?.close()
        }
    }

    private suspend fun writeAuthorization(
        path: String,
        capability: RisingStonesCapability,
    ): ForumWriteAuthorization {
        val provider = sessionProvider ?: throw OfficialForumException.AuthenticationRequired
        val context = RisingStonesRequestContext(
            path,
            RisingStonesAuthenticationRequirement.Required,
            capability,
        )
        if (provider is RisingStonesExplicitCapabilityProvider) {
            val attempt = provider.beginCapabilityAttempt(context)
                ?: throw OfficialForumException.AuthenticationRequired
            return ForumWriteAuthorization(attempt.authorizer, attempt)
        }
        if (capability !in provider.capabilities) throw OfficialForumException.AuthenticationRequired
        return ForumWriteAuthorization(
            provider.currentAuthorizer() ?: throw OfficialForumException.AuthenticationRequired,
            null,
        )
    }

    private suspend fun refreshAfterWriteAuthenticationFailure(): Nothing {
        try {
            sessionProvider?.refreshAuthorizer()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Refresh only lets the provider revoke stale capabilities. Never retry this write.
        }
        throw OfficialForumException.AuthenticationRequired
    }

    private fun formDeleteRequest(
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
    ) = RisingStonesApiRequest(
        path = path,
        method = RisingStonesHttpMethod.Delete,
        query = listOf(query("tempsuid", temporarySessionId)),
        headers = headers,
        body = body,
        contentType = "application/x-www-form-urlencoded; charset=utf-8",
    )

    private fun <T : ApiResponse> T.verified(): T {
        if (RisingStonesResponsePolicy.accepts(code)) return this
        if (code == 10105) throw OfficialForumException.IdentityConflict
        if (isAuthenticationFailure()) throw OfficialForumException.AuthenticationRequired
        throw OfficialForumException.Business(code, message)
    }

    private companion object {
        val temporarySessionId: String = UUID.randomUUID().toString()
        const val MaximumCommentImageBytes = 21 * 1024 * 1024
    }
}

private inline fun <T> normalizeFinalAuthenticationFailure(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    if (!error.isHttpIdentityConflict() && error.isHttpAuthenticationFailure()) {
        throw OfficialForumException.AuthenticationRequired
    }
    throw error
}

private fun safeObjectDirectory(value: String): Boolean = value.isNotBlank() &&
    value.split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    }

private suspend fun RisingStonesRequestAuthorizer?.headers(
    path: String,
    requirement: RisingStonesAuthenticationRequirement,
    capability: RisingStonesCapability? = null,
): Map<String, String> {
    val authorizer = this ?: return emptyMap()
    val headers = linkedMapOf<String, String>()
    authorizer.authorize(
        context = RisingStonesRequestContext(path, requirement, capability),
        sink = RisingStonesHeaderSink(headers::set),
    )
    return headers
}

private fun query(name: String, value: Any) = RisingStonesApiQueryItem(name, value.toString())

private fun String.formEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun JsonObject.mentionString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun String.isCommentMentionIdentity(): Boolean = isNotBlank() &&
    none { it == '#' || it.isISOControl() || it == '\u2028' || it == '\u2029' }

private interface ApiResponse { val code: Int?; val message: String? }

private fun ApiResponse.isIdentityConflict(): Boolean = code == 10105

private fun ApiResponse.isAuthenticationFailure(): Boolean {
    if (RisingStonesResponsePolicy.accepts(code) || code == 10105) return false
    if (code in setOf(401, 403, 10003, 10004, 10005)) return true
    val normalized = message?.lowercase().orEmpty()
    return listOf(
        "未登录", "登录失效", "登录过期", "授权失效", "凭据失效", "token失效",
        "token expired", "unauthorized", "not logged", "login expired", "session expired",
    ).any(normalized::contains)
}

private fun Throwable.isHttpAuthenticationFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is RisingStonesHttpException.ServerResponse &&
            current.statusCode in setOf(401, 403)
        ) return true
        current = current.cause
    }
    return false
}

private fun Throwable.isHttpIdentityConflict(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is RisingStonesHttpException.ServerResponse) {
            val code = runCatching {
                Json.parseToJsonElement(current.responseBody.decodeToString())
                    .jsonObject["code"]?.jsonPrimitive?.let { value ->
                        value.intOrNull ?: value.contentOrNull?.toIntOrNull()
                    }
            }.getOrNull()
            if (code == 10105) return true
        }
        current = current.cause
    }
    return false
}

@Serializable
private data class PartListResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: List<PartDto>? = null,
) : ApiResponse

@Serializable
private data class PostListResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: PostListPayload? = null,
) : ApiResponse

@Serializable
private data class PostDetailResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: JsonObject? = null,
) : ApiResponse

@Serializable
private data class CommentListResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: CommentListPayload? = null,
) : ApiResponse

@Serializable
private data class MutationResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: JsonElement = JsonNull,
) : ApiResponse

@Serializable
private data class CommentSubmitResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: List<Int>? = null,
) : ApiResponse

@Serializable
private data class CosTokenResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: CosTokenPayload? = null,
) : ApiResponse

@Serializable
private data class CosTokenPayload(
    val credentials: CosTemporaryCredentials,
    @SerialName("startTime") val startTime: Long,
    @SerialName("expiredTime") val expiredTime: Long,
    @SerialName("keyDir") val keyDir: String,
)

@Serializable
private data class CosTemporaryCredentials(
    @SerialName("sessionToken") val sessionToken: String,
    @SerialName("tmpSecretId") val tmpSecretId: String,
    @SerialName("tmpSecretKey") val tmpSecretKey: String,
)

internal object RisingStonesCos {
    private val random = SecureRandom()

    fun objectName(mimeType: String, now: Long = System.currentTimeMillis()): String {
        val extension = when (mimeType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            else -> throw OfficialForumException.ImageUploadFailed
        }
        return "%d_%04x%04x%d.%s".format(now, random.nextInt(1 shl 16), random.nextInt(1 shl 16), now, extension)
    }

    fun authorization(
        url: String,
        contentLength: Int,
        secretId: String,
        secretKey: String,
        startTime: Long,
        expiredTime: Long,
    ): String = try {
        top.cxmeow.risingstones.network.RisingStonesCosSigning.putAuthorization(
            url, contentLength, secretId, secretKey, startTime, expiredTime,
        )
    } catch (_: IllegalArgumentException) {
        throw OfficialForumException.ImageUploadFailed
    }

}

@Serializable
private data class VoteSubmitResponse(
    override val code: Int? = null,
    @SerialName("msg") override val message: String? = null,
    val data: JsonObject? = null,
) : ApiResponse

@Serializable
private data class VoteSubmitOption(
    val option: String,
    @SerialName("option_id") val optionId: Int,
)

@Serializable
private data class PartDto(
    val id: JsonElement = JsonNull,
    val name: String? = null,
    val status: JsonElement = JsonNull,
    val weight: JsonElement = JsonNull,
    val children: List<PartDto> = emptyList(),
) {
    fun domain(): OfficialForumPartFilter? {
        if ((status.intValue ?: 1) != 1) return null
        return OfficialForumPartFilter(
            id.intValue ?: return null,
            name.nonEmpty() ?: "未命名板块",
            weight.intValue ?: 0,
        )
    }
}

private fun List<PartDto>.categories(): List<OfficialForumCategory> = mapNotNull { dto ->
    dto.domain()?.let { OfficialForumCategory(it, dto.children.categories()) }
}.sortedWith(compareByDescending<OfficialForumCategory> { it.part.weight }.thenBy { it.part.id })

@Serializable
private data class PostListPayload(
    val count: JsonElement = JsonNull,
    val rows: List<PostRowDto> = emptyList(),
    val pageTime: JsonElement = JsonNull,
)

private fun PostListPayload?.page(page: Int, limit: Int): OfficialForumPage<OfficialForumPostSummary> {
    val rows = this?.rows.orEmpty()
    return OfficialForumPage(
        rows.mapNotNull(PostRowDto::domain),
        inferredTotal(this?.count.intValue, rows.size, page, limit),
        page,
    )
}

@Serializable
private data class PostRowDto(
    @SerialName("posts_id") val postsId: JsonElement = JsonNull,
    val uuid: String? = null,
    @SerialName("character_name") val characterName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val avatar: String? = null,
    @SerialName("admin_tag") val adminTag: JsonElement = JsonNull,
    @SerialName("part_id") val partId: JsonElement = JsonNull,
    @SerialName("part_name") val partName: String? = null,
    @SerialName("part_parent_name") val partParentName: String? = null,
    val title: String? = null,
    @SerialName("cover_pic") val coverPic: String? = null,
    @SerialName("content_pre") val contentPre: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_comment_time") val lastCommentTime: String? = null,
    @SerialName("comment_count") val commentCount: JsonElement = JsonNull,
    @SerialName("like_count") val likeCount: JsonElement = JsonNull,
    @SerialName("star_count") val starCount: JsonElement = JsonNull,
    @SerialName("read_count") val readCount: JsonElement = JsonNull,
    @SerialName("is_top") val isTop: JsonElement = JsonNull,
    @SerialName("is_refine") val isRefine: JsonElement = JsonNull,
) {
    fun domain(): OfficialForumPostSummary? = OfficialForumPostSummary(
        id = postsId.intValue ?: return null,
        title = title.nonEmpty() ?: "未命名主题",
        excerpt = OfficialForumHtml.plainText(contentPre.orEmpty()),
        author = author(),
        part = part(),
        coverImageUrls = OfficialForumHtml.imageUrls(coverPic),
        createdAt = createdAt.risingStonesInstant(),
        lastCommentAt = lastCommentTime.risingStonesInstant(),
        commentCount = commentCount.intValue ?: 0,
        likeCount = likeCount.intValue ?: 0,
        starCount = starCount.intValue ?: 0,
        readCount = readCount.intValue ?: 0,
        isTop = isTop.intValue == 1,
        isRefined = isRefine.intValue == 1,
    )

    private fun author() = OfficialForumAuthor(
        uuid.orEmpty(), characterName.nonEmpty() ?: "未知冒险者", areaName.orEmpty(),
        groupName.orEmpty(), avatar.remoteUrl(), adminTag.intValue ?: 0,
    )

    private fun part() = OfficialForumPart(
        partId.intValue, partName.nonEmpty() ?: "石之家", partParentName.orEmpty(),
    )
}

@Serializable
private data class PostDetailDto(
    val id: JsonElement = JsonNull,
    val uuid: String? = null,
    @SerialName("character_name") val characterName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val avatar: String? = null,
    @SerialName("admin_tag") val adminTag: JsonElement = JsonNull,
    val title: String? = null,
    @SerialName("cover_pic") val coverPic: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_comment_time") val lastCommentTime: String? = null,
    @SerialName("comment_count") val commentCount: JsonElement = JsonNull,
    @SerialName("like_count") val likeCount: JsonElement = JsonNull,
    @SerialName("star_count") val starCount: JsonElement = JsonNull,
    @SerialName("read_count") val readCount: JsonElement = JsonNull,
    @SerialName("ip_location") val ipLocation: String? = null,
    @SerialName("is_top") val isTop: JsonElement = JsonNull,
    @SerialName("is_refine") val isRefine: JsonElement = JsonNull,
    @SerialName("is_like") val isLike: JsonElement = JsonNull,
    @SerialName("is_star") val isStar: JsonElement = JsonNull,
    @SerialName("contentInfo") val contentInfo: ContentInfoDto? = null,
    @SerialName("content_info") val legacyContentInfo: ContentInfoDto? = null,
    @SerialName("userInfo") val userInfo: UserInfoDto? = null,
    @SerialName("user_info") val legacyUserInfo: UserInfoDto? = null,
    @SerialName("partInfo") val partInfo: PartInfoDto? = null,
    @SerialName("part_info") val legacyPartInfo: PartInfoDto? = null,
    @SerialName("voteInfo") val voteInfo: List<VoteOptionDto>? = null,
    @SerialName("vote_info") val legacyVoteInfo: List<VoteOptionDto>? = null,
    @SerialName("vote_total_user") val voteTotalUser: JsonElement = JsonNull,
) {
    fun domain(): OfficialForumPostDetail? {
        val resolvedContentInfo = contentInfo ?: legacyContentInfo
        val resolvedPartInfo = partInfo ?: legacyPartInfo
        val content = resolvedContentInfo?.content.orEmpty()
        val contentImages = OfficialForumHtml.imageUrls(content)
            .ifEmpty { OfficialForumHtml.imageUrls(coverPic) }
        val bodySegments = OfficialForumHtml.richSegments(content)
        return OfficialForumPostDetail(
            id = id.intValue ?: return null,
            title = title.nonEmpty() ?: "未命名主题",
            bodyHtml = content,
            bodyText = OfficialForumHtml.plainText(content),
            bodySegments = bodySegments,
            bodyBlocks = OfficialForumHtml.postBlocks(content, bodySegments),
            contentImageUrls = contentImages,
            votes = votes(),
            author = author(),
            part = OfficialForumPart(
                resolvedPartInfo?.id.intValue,
                resolvedPartInfo?.name.nonEmpty() ?: "石之家",
                resolvedPartInfo?.parentPartName.orEmpty(),
            ),
            createdAt = createdAt.risingStonesInstant(),
            updatedAt = updatedAt.risingStonesInstant(),
            lastCommentAt = lastCommentTime.risingStonesInstant(),
            commentCount = commentCount.intValue ?: 0,
            likeCount = likeCount.intValue ?: 0,
            starCount = starCount.intValue ?: 0,
            isLiked = isLike.optionalBoolean,
            isStarred = isStar.optionalBoolean,
            readCount = readCount.intValue ?: 0,
            ipLocation = ipLocation,
            isTop = isTop.intValue == 1,
            isRefined = isRefine.intValue == 1,
        )
    }

    private fun author(): OfficialForumAuthor {
        val resolvedUserInfo = userInfo ?: legacyUserInfo
        return OfficialForumAuthor(
            resolvedUserInfo?.uuid ?: uuid.orEmpty(),
            resolvedUserInfo?.characterName.nonEmpty() ?: characterName.nonEmpty() ?: "未知冒险者",
            resolvedUserInfo?.areaName ?: areaName.orEmpty(),
            resolvedUserInfo?.groupName ?: groupName.orEmpty(),
            (resolvedUserInfo?.avatar ?: avatar).remoteUrl(),
            resolvedUserInfo?.adminTag.intValue ?: adminTag.intValue ?: 0,
        )
    }

    private fun votes(): List<OfficialForumPostVote> = (voteInfo ?: legacyVoteInfo).orEmpty()
        .mapNotNull { row -> row.voteTitle.nonEmpty()?.let { row to it } }
        .groupBy { (row, title) ->
            listOf(row.postsId.intValue?.toString().orEmpty(), row.voteType.intValue ?: 0, title)
                .joinToString("|")
        }
        .mapNotNull { (voteId, pairs) ->
            val metadata = pairs.first().first
            val options = pairs.mapNotNull { it.first.domain() }.sortedBy { it.optionId }
            if (options.isEmpty()) return@mapNotNull null
            OfficialForumPostVote(
                voteId, pairs.first().second, metadata.voteType.intValue ?: 1,
                metadata.minMutiChoice.intValue, metadata.maxMutiChoice.intValue,
                metadata.levelRequire.intValue ?: 0, metadata.endDate.nonEmpty(),
                voteTotalUser.intValue ?: 0, options,
            )
        }
}

@Serializable private data class ContentInfoDto(val content: String? = null)

@Serializable
private data class UserInfoDto(
    val uuid: String? = null,
    @SerialName("character_name") val characterName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val avatar: String? = null,
    @SerialName("admin_tag") val adminTag: JsonElement = JsonNull,
)

@Serializable
private data class PartInfoDto(
    val id: JsonElement = JsonNull,
    val name: String? = null,
    @SerialName("parent_part_name") val parentPartName: String? = null,
)

@Serializable
private data class VoteOptionDto(
    val id: JsonElement = JsonNull,
    @SerialName("posts_id") val postsId: JsonElement = JsonNull,
    @SerialName("vote_type") val voteType: JsonElement = JsonNull,
    @SerialName("vote_title") val voteTitle: String? = null,
    @SerialName("option_id") val optionId: JsonElement = JsonNull,
    val option: String? = null,
    @SerialName("option_des") val optionDescription: String? = null,
    @SerialName("option_type") val optionType: JsonElement = JsonNull,
    @SerialName("min_muti_choice") val minMutiChoice: JsonElement = JsonNull,
    @SerialName("max_muti_choice") val maxMutiChoice: JsonElement = JsonNull,
    @SerialName("level_require") val levelRequire: JsonElement = JsonNull,
    @SerialName("total_vote_num") val totalVoteNum: JsonElement = JsonNull,
    @SerialName("is_participant") val isParticipant: JsonElement = JsonNull,
    @SerialName("end_date") val endDate: String? = null,
) {
    fun domain(): OfficialForumPostVoteOption? {
        val rowId = id.intValue ?: return null
        return OfficialForumPostVoteOption(
            id = rowId.toString(),
            optionId = optionId.intValue ?: rowId,
            title = option.nonEmpty() ?: return null,
            description = optionDescription.nonEmpty(),
            type = optionType.intValue ?: 1,
            totalVoteCount = totalVoteNum.intValue ?: 0,
            isParticipant = isParticipant.intValue == 1,
        )
    }
}

@Serializable
private data class CommentListPayload(
    val count: JsonElement = JsonNull,
    val rows: List<CommentDto> = emptyList(),
)

private fun CommentListPayload?.page(page: Int, limit: Int): OfficialForumPage<OfficialForumComment> {
    val rows = this?.rows.orEmpty()
    return OfficialForumPage(
        rows.mapNotNull(CommentDto::domain),
        inferredTotal(this?.count.intValue, rows.size, page, limit),
        page,
    )
}

@Serializable
private data class CommentDto(
    val id: JsonElement = JsonNull,
    @SerialName("children_count") val childrenCount: JsonElement = JsonNull,
    @SerialName("mask_content") val maskContent: String? = null,
    @SerialName("comment_pic") val commentPic: String? = null,
    val uuid: String? = null,
    @SerialName("character_name") val characterName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("ip_location") val ipLocation: String? = null,
    @SerialName("like_count") val likeCount: JsonElement = JsonNull,
    val avatar: String? = null,
    @SerialName("admin_tag") val adminTag: JsonElement = JsonNull,
    @SerialName("is_posts_author") val isPostsAuthor: JsonElement = JsonNull,
    @SerialName("to_cname") val toCharacterName: String? = null,
    @SerialName("is_mine") val isMine: JsonElement = JsonNull,
    @SerialName("is_like") val isLike: JsonElement = JsonNull,
) {
    fun domain(): OfficialForumComment? {
        val content = maskContent.orEmpty()
        return OfficialForumComment(
            id = id.intValue ?: return null,
            author = OfficialForumAuthor(
                uuid.orEmpty(), characterName.nonEmpty() ?: "未知冒险者", areaName.orEmpty(),
                groupName.orEmpty(), avatar.remoteUrl(), adminTag.intValue ?: 0,
            ),
            replyToAuthorName = toCharacterName.nonEmpty(),
            bodyText = OfficialForumHtml.plainText(content),
            bodySegments = OfficialForumHtml.richSegments(content),
            imageUrls = (
                OfficialForumHtml.imageUrls(content) + OfficialForumHtml.imageUrls(commentPic)
                ).distinct(),
            createdAt = createdAt.risingStonesInstant(),
            ipLocation = ipLocation,
            likeCount = likeCount.intValue ?: 0,
            isLiked = isLike.intValue == 1,
            childCount = childrenCount.intValue ?: 0,
            isPostAuthor = isPostsAuthor.intValue == 1,
            isMine = isMine.intValue == 1,
        )
    }
}

private val JsonElement?.intValue: Int?
    get() = when (this) {
        is JsonPrimitive -> intOrNull ?: contentOrNull?.toIntOrNull()
        else -> null
    }

private val JsonElement?.stringValue: String?
    get() = (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private val JsonElement?.optionalBoolean: Boolean?
    get() = intValue?.let { it == 1 }

private fun String?.nonEmpty(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.remoteUrl(): String? = OfficialForumHtml.imageUrls(this).firstOrNull()

private fun String?.risingStonesInstant(): Instant? {
    val value = nonEmpty() ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant()
        }.getOrNull()
}

private fun inferredTotal(reported: Int?, rows: Int, page: Int, limit: Int): Int =
    reported ?: ((page.coerceAtLeast(1) - 1) * limit.coerceAtLeast(1) + rows).let { loaded ->
        if (rows >= limit.coerceAtLeast(1)) loaded + 1 else loaded
    }
