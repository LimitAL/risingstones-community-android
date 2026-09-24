package top.cxmeow.risingstones.feature.dynamic.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.network.*

internal class DynamicActionApiDelegate(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json,
) {
    val canPerformAuthenticatedWrites: Boolean
        get() = RisingStonesCapability.DynamicWrite in sessionProvider.capabilities

    val canAttemptAuthenticatedWrites: Boolean
        get() = sessionProvider is RisingStonesCapabilityScopeProvider &&
            (sessionProvider as? RisingStonesExplicitCapabilityProvider)
                ?.canAttemptCapability(RisingStonesCapability.DynamicWrite) == true

    suspend fun beginActionScope(): DynamicActionScope {
        val provider = sessionProvider as? RisingStonesCapabilityScopeProvider
            ?: throw DynamicException.Unavailable
        val scope = provider.captureCapabilityScope(
            setOf(
                RisingStonesCapability.DynamicWrite,
                RisingStonesCapability.DynamicImageUpload,
            ),
        ) ?: throw DynamicException.AuthenticationRequired
        return OfficialDynamicActionScope(scope, sessionProvider)
    }

    suspend fun entryEligibility(scope: DynamicActionScope, dynamicId: Int): DynamicEntryActionEligibility {
        require(dynamicId > 0)
        val official = scope.officialFor(sessionProvider)
        val value = readObject(official, DynamicPath + "dynamicDetail", listOf(q("id", dynamicId)))
        if (value.number("id") != dynamicId) throw DynamicException.InvalidResponse
        return DynamicEntryActionEligibility(
            dynamicId,
            relation(identity(official), value.identity("uuid")),
        )
    }

    suspend fun commentEligibility(scope: DynamicActionScope, commentId: Int): DynamicCommentActionEligibility {
        require(commentId > 0)
        val official = scope.officialFor(sessionProvider)
        official.requireCurrent()
        val author = if (official.observedCommentAuthors.containsKey(commentId)) {
            official.observedCommentAuthors[commentId]
        } else null
        return DynamicCommentActionEligibility(
            commentId,
            if (!official.observedCommentAuthors.containsKey(commentId)) DynamicActionEligibility.Unknown
            else relation(identity(official), author),
        )
    }

    suspend fun fetchMentionCandidates(
        scope: DynamicActionScope,
        query: DynamicListQuery,
    ): DynamicPage<DynamicCommentMention> {
        val data = readObject(
            scope.officialFor(sessionProvider),
            "api/home/userRelation/followList",
            query.parameters(),
        )
        val rows = data["rows"] as? JsonArray ?: throw DynamicException.InvalidResponse
        val candidates = rows.mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val uuid = value.mentionIdentity("uuid") ?: return@mapNotNull null
            val name = value.mentionIdentity("character_name") ?: return@mapNotNull null
            DynamicCommentMention(uuid, name)
        }
        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceIn(1, 100)
        return DynamicPage(
            items = candidates,
            page = page,
            hasMore = data.number("count")?.let { page * limit < it } ?: (rows.size >= limit),
            pageTime = (data["pageTime"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    suspend fun fetchComments(
        scope: DynamicActionScope,
        dynamicId: Int,
        query: DynamicListQuery,
    ): DynamicPage<DynamicComment> {
        require(dynamicId > 0)
        val official = scope.officialFor(sessionProvider)
        return observedComments(
            official,
            readObject(official, DynamicPath + "dynamicCommentDetail", listOf(q("id", dynamicId)) + query.parameters()),
            query,
        )
    }

    suspend fun fetchReplies(
        scope: DynamicActionScope,
        rootParentId: Int,
        query: DynamicListQuery,
    ): DynamicPage<DynamicComment> {
        require(rootParentId > 0)
        val official = scope.officialFor(sessionProvider)
        return observedComments(
            official,
            readObject(
                official,
                DynamicPath + "dynamicSubCommentDetail",
                listOf(q("root_parent", rootParentId), q("order", "earliest")) + query.parameters(),
            ),
            query,
        )
    }

    suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int): DynamicLikeResult {
        require(dynamicId > 0)
        return write(
            scope.officialFor(sessionProvider),
            DynamicPath + "like",
            RisingStonesHttpMethod.Post,
            listOf("id" to dynamicId.toString()),
        ) { data ->
            when ((data as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull) {
                1 -> DynamicLikeResult.Liked
                -1 -> DynamicLikeResult.Unliked
                else -> throw DynamicException.InvalidResponse
            }
        }
    }

    suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft) {
        val image = draft.commentImage?.boundTo(scope, DynamicImagePurpose.Comment)?.url.orEmpty()
        val fields = buildList {
            draft.mentions.distinct().forEachIndexed { index, mention ->
                add("atInfo[$index][uuid]" to mention.uuid)
                add("atInfo[$index][character_name]" to mention.characterName)
            }
            add("content" to draft.contentHtml)
            add("dynamic_id" to draft.dynamicId.toString())
            add("parent_id" to draft.parentId.toString())
            add("root_parent" to draft.rootParentId.toString())
            add("comment_pic" to image)
        }
        write(
            scope.officialFor(sessionProvider),
            DynamicPath + "comment",
            RisingStonesHttpMethod.Post,
            fields,
            acceptedCodes = setOf(10000),
        ) { Unit }
    }

    suspend fun publish(scope: DynamicActionScope, draft: DynamicPublishDraft) {
        val imageUrls = draft.images.map { image ->
            image.boundTo(scope, DynamicImagePurpose.Publishing).url
        }
        write(
            scope.officialFor(sessionProvider),
            DynamicPath + "create",
            RisingStonesHttpMethod.Post,
            mentionFields(draft.mentions) + listOf(
                "content" to draft.contentHtml,
                "scope" to draft.visibility.wireValue.toString(),
                "pic_url" to imageUrls.joinToString(","),
            ),
        ) { Unit }
    }

    suspend fun relayPost(scope: DynamicActionScope, draft: DynamicPostRelayDraft) {
        write(
            scope.officialFor(sessionProvider),
            "api/home/posts/relay",
            RisingStonesHttpMethod.Post,
            mentionFields(draft.mentions) + listOf(
                "content" to draft.contentHtml.ifEmpty { DefaultRelayContent },
                "scope" to draft.visibility.wireValue.toString(),
                "posts_id" to draft.postId.toString(),
            ),
        ) { Unit }
    }

    suspend fun relayRecruitment(scope: DynamicActionScope, draft: DynamicRecruitmentRelayDraft) {
        write(
            scope.officialFor(sessionProvider),
            "api/home/recruit/relay",
            RisingStonesHttpMethod.Post,
            listOf(
                "from" to draft.origin.wireValue.toString(),
                "scope" to draft.visibility.wireValue.toString(),
                "recruid_id" to draft.recruitmentId.toString(),
            ),
        ) { Unit }
    }

    suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int) {
        requireEligible(commentEligibility(scope, commentId).deleteOwnComment)
        write(
            scope.officialFor(sessionProvider),
            DynamicPath + "deleteComment",
            RisingStonesHttpMethod.Delete,
            listOf("comment_id" to commentId.toString()),
        ) { Unit }
    }

    suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int) {
        requireEligible(entryEligibility(scope, dynamicId).deleteOwnDynamic)
        write(
            scope.officialFor(sessionProvider),
            DynamicPath + "deleteDynamic",
            RisingStonesHttpMethod.Delete,
            listOf("dynamic_id" to dynamicId.toString()),
        ) { Unit }
    }

    private suspend fun identity(scope: OfficialDynamicActionScope): String? {
        scope.requireCurrent()
        scope.identityMutex.lock()
        try {
            scope.requireCurrent()
            when (val cached = scope.currentIdentity) {
                DynamicIdentityState.Unread -> Unit
                DynamicIdentityState.Unknown -> return null
                is DynamicIdentityState.Known -> return cached.uuid
            }
            val uuid = readObject(
                scope,
                "api/home/groupAndRole/getCharacterBindInfo",
                listOf(q("platform", 1)),
            ).identity("uuid")
            scope.currentIdentity = uuid?.let(DynamicIdentityState::Known) ?: DynamicIdentityState.Unknown
            return uuid
        } finally {
            scope.identityMutex.unlock()
        }
    }

    private fun observedComments(
        scope: OfficialDynamicActionScope,
        data: JsonObject,
        query: DynamicListQuery,
    ): DynamicPage<DynamicComment> {
        val result = data.page(query, ::comment)
        val rows = data["rows"] as? JsonArray ?: throw DynamicException.InvalidResponse
        val authors = rows.associate { row ->
            val item = row as? JsonObject ?: throw DynamicException.InvalidResponse
            val id = item.number("id")?.takeIf { it > 0 } ?: throw DynamicException.InvalidResponse
            id to item.identity("uuid")
        }
        scope.observedCommentAuthors.putAll(authors)
        return result
    }

    private suspend fun readObject(
        scope: OfficialDynamicActionScope,
        path: String,
        query: List<RisingStonesApiQueryItem> = emptyList(),
    ): JsonObject {
        scope.requireCurrent()
        val headers = linkedMapOf<String, String>()
        val context = RisingStonesRequestContext(
            path,
            RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.DynamicRead,
        )
        try {
            scope.delegate.authorizer.authorize(context, RisingStonesHeaderSink { name, value -> headers[name] = value })
            val response = try {
                client.execute(RisingStonesApiRequest(path, query = query, headers = headers))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.serverStatus() in setOf(401, 403)) {
                    refreshAfterAuthenticationFailure()
                    throw DynamicException.AuthenticationRequired
                }
                throw DynamicException.Network
            }
            scope.requireCurrent()
            if (response.statusCode == 401 || response.statusCode == 403) {
                refreshAfterAuthenticationFailure()
                throw DynamicException.AuthenticationRequired
            }
            if (response.statusCode != 200) throw DynamicException.Network
            val envelope = try {
                json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: throw DynamicException.InvalidResponse
            val code = envelope.strictInt("code") ?: throw DynamicException.InvalidResponse
            if (code == 10105) throw DynamicException.IdentityConflict
            if (code in AuthenticationFailureCodes) {
                refreshAfterAuthenticationFailure()
                throw DynamicException.AuthenticationRequired
            }
            if (code !in setOf(10000, 10002)) throw DynamicException.Business(code)
            return envelope["data"] as? JsonObject ?: throw DynamicException.InvalidResponse
        } catch (error: CancellationException) {
            throw error
        } catch (error: DynamicException) {
            throw error
        } catch (_: Exception) {
            scope.requireCurrent()
            throw DynamicException.Network
        }
    }

    private suspend fun <T> write(
        scope: OfficialDynamicActionScope,
        path: String,
        method: RisingStonesHttpMethod,
        fields: List<Pair<String, String>>,
        acceptedCodes: Set<Int> = setOf(10000, 10002),
        validate: (JsonElement?) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        scope.requireCurrent()
        val context = RisingStonesRequestContext(
            path,
            RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.DynamicWrite,
        )
        val attempt = scope.delegate.beginCapabilityAttempt(context)
            ?: throw DynamicException.AuthenticationRequired
        try {
            val guard = attempt as? RisingStonesCapabilityAttemptGuard
                ?: throw DynamicException.AuthenticationRequired
            val headers = linkedMapOf<String, String>()
            try {
                attempt.authorizer.authorize(context, RisingStonesHeaderSink { name, value -> headers[name] = value })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw DynamicException.AuthenticationRequired
            }
            if (!guard.isCurrent()) throw DynamicException.AuthenticationRequired
            val response = try {
                client.execute(
                    RisingStonesApiRequest(
                        path = path,
                        method = method,
                        headers = headers,
                        body = fields.formBody(),
                        contentType = FormContentType,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.serverStatus() in setOf(401, 403)) {
                    refreshAfterAuthenticationFailure()
                    throw DynamicException.AuthenticationRequired
                }
                throw DynamicException.Network
            }
            if (!guard.isCurrent()) throw DynamicException.AuthenticationRequired
            if (response.statusCode == 401 || response.statusCode == 403) {
                refreshAfterAuthenticationFailure()
                throw DynamicException.AuthenticationRequired
            }
            if (response.statusCode != 200) throw DynamicException.Network
            val envelope = try {
                json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
            } catch (_: IllegalArgumentException) { null } ?: throw DynamicException.InvalidResponse
            val code = envelope.strictInt("code") ?: throw DynamicException.InvalidResponse
            when {
                code == 10105 -> throw DynamicException.IdentityConflict
                code in AuthenticationFailureCodes -> {
                    refreshAfterAuthenticationFailure()
                    throw DynamicException.AuthenticationRequired
                }
                code !in acceptedCodes -> throw DynamicException.Business(code)
            }
            val result = validate(envelope["data"])
            scope.requireCurrent()
            if (!guard.isCurrent() || !attempt.complete()) throw DynamicException.AuthenticationRequired
            scope.requireCurrent()
            return result
        } finally {
            attempt.close()
        }
    }

    private suspend fun refreshAfterAuthenticationFailure() {
        try {
            sessionProvider.refreshAuthorizer()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The failed write is authoritative and is never replayed.
        }
    }
}

private suspend fun OfficialDynamicActionScope.requireCurrent() {
    currentCoroutineContext().ensureActive()
    if (!delegate.isCurrent()) throw DynamicException.AuthenticationRequired
}

private fun DynamicActionScope.officialFor(provider: RisingStonesSessionProvider): OfficialDynamicActionScope {
    val official = this as? OfficialDynamicActionScope ?: throw DynamicException.AuthenticationRequired
    if (official.sessionProvider !== provider) throw DynamicException.AuthenticationRequired
    return official
}

private fun DynamicUploadedImage.boundTo(
    scope: DynamicActionScope,
    purpose: DynamicImagePurpose,
): BoundDynamicUploadedImage {
    val official = scope as? OfficialDynamicActionScope ?: throw DynamicException.AuthenticationRequired
    val image = this as? BoundDynamicUploadedImage ?: throw DynamicException.ActionNotEligible
    if (image.actionScope !== official || image.purpose != purpose) throw DynamicException.ActionNotEligible
    return image
}

private fun mentionFields(mentions: List<DynamicCommentMention>): List<Pair<String, String>> = buildList {
    mentions.forEachIndexed { index, mention ->
        add("atInfo[$index][uuid]" to mention.uuid)
        add("atInfo[$index][character_name]" to mention.characterName)
    }
}

private val DynamicVisibility.wireValue: Int
    get() = when (this) {
        DynamicVisibility.Public -> 1
        DynamicVisibility.MutualFollowers -> 2
        DynamicVisibility.OnlyMe -> 3
    }

private fun requireEligible(value: DynamicActionEligibility) {
    if (value != DynamicActionEligibility.Eligible) throw DynamicException.ActionNotEligible
}

private fun relation(left: String?, right: String?): DynamicActionEligibility = when {
    left == null || right == null -> DynamicActionEligibility.Unknown
    left == right -> DynamicActionEligibility.Eligible
    else -> DynamicActionEligibility.Ineligible
}

private fun List<Pair<String, String>>.formBody(): ByteArray = joinToString("&") { (name, value) ->
    "${name.formEncode()}=${value.formEncode()}"
}.encodeToByteArray()

private fun String.formEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    .replace("+", "%20")

private fun JsonObject.strictInt(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.isString) return null
    return primitive.intOrNull
}

private fun JsonObject.identity(name: String): String? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw DynamicException.InvalidResponse
    if (!primitive.isString) throw DynamicException.InvalidResponse
    val value = primitive.contentOrNull ?: throw DynamicException.InvalidResponse
    return value.takeIf {
        it.isNotBlank() && it == it.trim() && '#' !in it && it.none(Char::isISOControl)
    }
}

private fun JsonObject.mentionIdentity(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotBlank() && it == it.trim() && '#' !in it && it.none(Char::isISOControl) }

private fun Throwable.serverStatus(): Int? = generateSequence(this) { it.cause }
    .take(8)
    .filterIsInstance<RisingStonesHttpException.ServerResponse>()
    .firstOrNull()
    ?.statusCode

private const val DynamicPath = "api/home/dynamic/"
private const val FormContentType = "application/x-www-form-urlencoded; charset=utf-8"
private const val DefaultRelayContent = "分享了内容："
private val AuthenticationFailureCodes = setOf(401, 403, 10001, 10003, 10004, 10005, 10403)
