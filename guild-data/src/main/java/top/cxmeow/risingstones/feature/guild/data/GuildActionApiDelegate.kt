package top.cxmeow.risingstones.feature.guild.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
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
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityAttemptGuard
import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScopeProvider
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildCommentActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildGuildActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.domain.GuildLabel
import top.cxmeow.risingstones.feature.guild.domain.GuildLabelId
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoActionEligibility
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoCommentDraft
import top.cxmeow.risingstones.feature.guild.domain.GuildPhotoLikeResult
import top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

internal class GuildCommentOwnershipRegistry {
    private val observedIds = ConcurrentHashMap.newKeySet<Int>()
    private val knownAuthors = ConcurrentHashMap<Int, String>()

    fun observe(commentId: Int, authorUuid: String?) {
        observedIds += commentId
        if (authorUuid == null) knownAuthors.remove(commentId) else knownAuthors[commentId] = authorUuid
    }

    fun lookup(commentId: Int): ObservedCommentAuthor = when {
        commentId !in observedIds -> ObservedCommentAuthor.Unobserved
        knownAuthors[commentId] == null -> ObservedCommentAuthor.Unknown
        else -> ObservedCommentAuthor.Known(knownAuthors.getValue(commentId))
    }
}

internal sealed interface ObservedCommentAuthor {
    data object Unobserved : ObservedCommentAuthor
    data object Unknown : ObservedCommentAuthor
    data class Known(val uuid: String) : ObservedCommentAuthor
}

internal class GuildActionApiDelegate(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json,
    private val commentOwnership: GuildCommentOwnershipRegistry,
) {
    val canPerformAuthenticatedWrites: Boolean
        get() = RisingStonesCapability.GuildWrite in sessionProvider.capabilities

    val canAttemptAuthenticatedWrites: Boolean
        get() = sessionProvider is RisingStonesCapabilityScopeProvider &&
            (sessionProvider as? RisingStonesExplicitCapabilityProvider)?.let { provider ->
                provider.canAttemptCapability(RisingStonesCapability.GuildWrite) &&
                    provider.canAttemptCapability(RisingStonesCapability.GuildImageUpload)
            } == true

    suspend fun beginActionScope(): GuildActionScope {
        currentCoroutineContext().ensureActive()
        val provider = sessionProvider as? RisingStonesCapabilityScopeProvider
            ?: throw GuildException.Unavailable
        val scope = provider.captureCapabilityScope(
            setOf(RisingStonesCapability.GuildWrite, RisingStonesCapability.GuildImageUpload),
        ) ?: throw GuildException.AuthenticationRequired
        return OfficialGuildActionScope(scope, sessionProvider)
    }

    suspend fun labels(scope: GuildActionScope): List<GuildLabel> {
        val official = scope.officialFor(sessionProvider)
        val data = read(official, LabelsPath)
        val rows = data as? JsonArray ?: throw GuildException.InvalidResponse
        return rows.map { row ->
            val item = row as? JsonObject ?: throw GuildException.InvalidResponse
            GuildLabel(
                id = GuildLabelId(item.requiredDecimal("id")),
                name = item.requiredText("name").takeIf(String::isNotBlank)
                    ?: throw GuildException.InvalidResponse,
            )
        }
    }

    suspend fun guildEligibility(
        scope: GuildActionScope,
        guildId: GuildId,
    ): GuildGuildActionEligibility {
        val official = scope.officialFor(sessionProvider)
        val identity = identity(official)
        val info = guildRelation(official, guildId)
        return GuildGuildActionEligibility(
            guildId = guildId,
            manageGuild = relation(identity.characterId, info.masterCharacterId),
            uploadAlbum = when (identity.guildIdState) {
                IdState.Unknown -> GuildActionEligibility.Unknown
                IdState.None -> GuildActionEligibility.Ineligible
                is IdState.Known -> if (identity.guildIdState.id == guildId) {
                    GuildActionEligibility.Eligible
                } else {
                    GuildActionEligibility.Ineligible
                }
            },
        )
    }

    suspend fun photoEligibility(
        scope: GuildActionScope,
        photoId: Int,
    ): GuildPhotoActionEligibility {
        require(photoId > 0)
        val official = scope.officialFor(sessionProvider)
        val identity = identity(official)
        val photo = photoRelation(official, photoId)
        val guild = guildRelation(official, photo.guildId)
        val authorMatch = relation(identity.uuid, photo.authorUuid)
        val masterMatch = relation(identity.characterId, guild.masterCharacterId)
        return GuildPhotoActionEligibility(
            photoId = photoId,
            guildId = photo.guildId,
            deletePhoto = orEligibility(authorMatch, masterMatch),
        )
    }

    suspend fun commentEligibility(
        scope: GuildActionScope,
        commentId: Int,
    ): GuildCommentActionEligibility {
        require(commentId > 0)
        val official = scope.officialFor(sessionProvider)
        val observed = commentOwnership.lookup(commentId)
        if (observed !is ObservedCommentAuthor.Known) {
            official.requireCurrent()
            return GuildCommentActionEligibility(commentId, GuildActionEligibility.Unknown)
        }
        val identity = identity(official)
        return GuildCommentActionEligibility(
            commentId,
            relation(identity.uuid, observed.uuid),
        )
    }

    suspend fun updateGuildInfo(
        scope: GuildActionScope,
        guildId: GuildId,
        update: GuildInfoUpdate,
    ) {
        requireEligible(guildEligibility(scope, guildId).manageGuild)
        val fields = mutableListOf("guild_id" to guildId.value)
        when (update) {
            is GuildInfoUpdate.Picture -> {
                fields += "key" to "guild_pic"
                fields += "guild_pic" to update.image.boundTo(scope).url
            }
            is GuildInfoUpdate.HousingVisibility -> {
                fields += "key" to "house_public"
                fields += "house_public" to if (update.visible) "1" else "0"
            }
            is GuildInfoUpdate.Labels -> {
                fields += "key" to "guild_label"
                fields += "guild_label" to update.ids.joinToString(",") { it.value }
            }
            is GuildInfoUpdate.WeekdayActiveTime -> {
                fields += "key" to "active_time_weekday"
                fields += "active_time_weekday" to update.range.officialValue
            }
            is GuildInfoUpdate.WeekendActiveTime -> {
                fields += "key" to "active_time_weekend"
                fields += "active_time_weekend" to update.range.officialValue
            }
            is GuildInfoUpdate.Description -> {
                fields += "key" to "guild_describe"
                fields += "guild_describe" to update.text
            }
        }
        write(scope.officialFor(sessionProvider), SetGuildInfoPath, RisingStonesHttpMethod.Post, fields = fields)
    }

    suspend fun togglePhotoLike(scope: GuildActionScope, photoId: Int): GuildPhotoLikeResult {
        require(photoId > 0)
        return write(
            scope.officialFor(sessionProvider),
            LikePhotoPath,
            RisingStonesHttpMethod.Post,
            fields = listOf("photo_id" to photoId.toString()),
        ) { data ->
            val primitive = data as? JsonPrimitive ?: throw GuildException.InvalidResponse
            val value = primitive.takeIf { !it.isString }?.intOrNull
                ?: throw GuildException.InvalidResponse
            when (value) {
                1 -> GuildPhotoLikeResult.Liked
                -1 -> GuildPhotoLikeResult.Unliked
                else -> throw GuildException.InvalidResponse
            }
        }
    }

    suspend fun commentPhoto(scope: GuildActionScope, draft: GuildPhotoCommentDraft) {
        val imageUrl = draft.commentImage?.boundTo(scope)?.url.orEmpty()
        val fields = buildList {
            draft.mentions.distinct().forEachIndexed { index, mention ->
                add("atInfo[$index][uuid]" to mention.uuid)
                add("atInfo[$index][character_name]" to mention.characterName)
            }
            add("content" to draft.contentHtml)
            add("photo_id" to draft.photoId.toString())
            add("parent_id" to draft.parentId.toString())
            add("root_parent" to draft.rootParentId.toString())
            add("comment_pic" to imageUrl)
        }
        write(
            scope.officialFor(sessionProvider),
            CommentPhotoPath,
            RisingStonesHttpMethod.Post,
            fields = fields,
            acceptedCodes = setOf(10000),
        )
    }

    suspend fun deleteOwnComment(scope: GuildActionScope, commentId: Int) {
        requireEligible(commentEligibility(scope, commentId).deleteOwnComment)
        write(
            scope.officialFor(sessionProvider),
            DeleteCommentPath,
            RisingStonesHttpMethod.Delete,
            fields = listOf("comment_id" to commentId.toString()),
        )
    }

    suspend fun registerAlbumPhotos(
        scope: GuildActionScope,
        guildId: GuildId,
        images: List<GuildUploadedImage>,
    ) {
        require(images.size in 1..9)
        requireEligible(guildEligibility(scope, guildId).uploadAlbum)
        val urls = images.map { image ->
            require(image.purpose == top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose.Album)
            image.boundTo(scope).url
        }
        write(
            scope.officialFor(sessionProvider),
            UploadGuildPhotoPath,
            RisingStonesHttpMethod.Post,
            fields = listOf("photo_url" to urls.joinToString(","), "guild_id" to guildId.value),
        )
    }

    suspend fun deletePhoto(scope: GuildActionScope, photoId: Int) {
        requireEligible(photoEligibility(scope, photoId).deletePhoto)
        write(
            scope.officialFor(sessionProvider),
            DeleteGuildPhotoPath,
            RisingStonesHttpMethod.Delete,
            query = listOf(query("photo_id", photoId)),
        )
    }

    private suspend fun identity(scope: OfficialGuildActionScope): CurrentIdentity {
        val data = read(scope, CharacterInfoPath, listOf(query("platform", 1))) as? JsonObject
            ?: throw GuildException.InvalidResponse
        return CurrentIdentity(
            characterId = data.optionalPositiveDecimal("character_id"),
            uuid = data.optionalIdentity("uuid"),
            guildIdState = (data["characterDetail"] as? JsonObject).guildIdState("fc_id"),
        )
    }

    private suspend fun guildRelation(
        scope: OfficialGuildActionScope,
        guildId: GuildId,
    ): GuildRelation {
        val data = read(scope, GuildInfoPath, listOf(query("guild_id", guildId.value))) as? JsonObject
            ?: throw GuildException.InvalidResponse
        val responseId = data.requiredPositiveDecimal("guild_id")
        if (responseId != guildId.value) throw GuildException.InvalidResponse
        return GuildRelation(data.optionalPositiveDecimal("master_id"))
    }

    private suspend fun photoRelation(
        scope: OfficialGuildActionScope,
        photoId: Int,
    ): PhotoRelation {
        val data = read(scope, PhotoDetailPath, listOf(query("id", photoId))) as? JsonObject
            ?: throw GuildException.InvalidResponse
        if (data.requiredPositiveInt("id") != photoId) throw GuildException.InvalidResponse
        return PhotoRelation(
            guildId = GuildId(data.requiredPositiveDecimal("guild_id")),
            authorUuid = data.optionalIdentity("uuid"),
        )
    }

    private suspend fun read(
        scope: OfficialGuildActionScope,
        path: String,
        query: List<RisingStonesApiQueryItem> = emptyList(),
    ): JsonElement {
        scope.requireCurrent()
        val data = try {
            readOnce(scope, path, query)
        } catch (error: GuildException.AuthenticationRequired) {
            if (!refreshAfterAuthenticationFailure()) throw error
            scope.requireCurrent()
            readOnce(scope, path, query)
        }
        scope.requireCurrent()
        return data
    }

    private suspend fun readOnce(
        scope: OfficialGuildActionScope,
        path: String,
        query: List<RisingStonesApiQueryItem>,
    ): JsonElement = try {
        readGuildPayload(client, json, scope.delegate.authorizer, path, query)
    } catch (error: CancellationException) {
        throw error
    } catch (error: GuildException) {
        throw error
    } catch (_: Exception) {
        scope.requireCurrent()
        throw GuildException.Network
    }

    private suspend fun <T> write(
        scope: OfficialGuildActionScope,
        path: String,
        method: RisingStonesHttpMethod,
        fields: List<Pair<String, String>> = emptyList(),
        query: List<RisingStonesApiQueryItem> = emptyList(),
        acceptedCodes: Set<Int> = setOf(10000, 10002),
        validate: (JsonElement?) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        scope.requireCurrent()
        val context = RisingStonesRequestContext(
            path = path,
            requirement = RisingStonesAuthenticationRequirement.Required,
            capability = RisingStonesCapability.GuildWrite,
        )
        val attempt = scope.delegate.beginCapabilityAttempt(context)
            ?: throw GuildException.AuthenticationRequired
        try {
            val guard = attempt as? RisingStonesCapabilityAttemptGuard
                ?: throw GuildException.AuthenticationRequired
            val headers = linkedMapOf<String, String>()
            try {
                attempt.authorizer.authorize(
                    context,
                    RisingStonesHeaderSink { name, value -> headers[name] = value },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw GuildException.AuthenticationRequired
            }
            currentCoroutineContext().ensureActive()
            if (!guard.isCurrent()) throw GuildException.AuthenticationRequired
            val request = RisingStonesApiRequest(
                path = path,
                method = method,
                query = query,
                headers = headers,
                body = fields.takeIf { it.isNotEmpty() }?.formBody(),
                contentType = FormContentType,
            )
            val response = try {
                client.execute(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val status = error.serverStatus()
                if (status == 401 || status == 403) {
                    refreshAfterAuthenticationFailure()
                    throw GuildException.AuthenticationRequired
                }
                throw GuildException.Network
            }
            currentCoroutineContext().ensureActive()
            if (!guard.isCurrent()) throw GuildException.AuthenticationRequired
            if (response.statusCode == 401 || response.statusCode == 403) {
                refreshAfterAuthenticationFailure()
                throw GuildException.AuthenticationRequired
            }
            if (response.statusCode != 200) throw GuildException.Network
            val envelope = parseWriteEnvelope(response.body)
            when {
                envelope.code == 10105 -> throw GuildException.IdentityConflict
                envelope.code in AuthenticationFailureCodes -> {
                    refreshAfterAuthenticationFailure()
                    throw GuildException.AuthenticationRequired
                }
                envelope.code !in acceptedCodes -> throw GuildException.Business(envelope.code)
            }
            val result = validate(envelope.data)
            currentCoroutineContext().ensureActive()
            scope.requireCurrent()
            if (!guard.isCurrent()) throw GuildException.AuthenticationRequired
            if (!attempt.complete()) throw GuildException.AuthenticationRequired
            return result
        } finally {
            attempt.close()
        }
    }

    private suspend fun write(
        scope: OfficialGuildActionScope,
        path: String,
        method: RisingStonesHttpMethod,
        fields: List<Pair<String, String>> = emptyList(),
        query: List<RisingStonesApiQueryItem> = emptyList(),
        acceptedCodes: Set<Int> = setOf(10000, 10002),
    ) {
        write(scope, path, method, fields, query, acceptedCodes) { Unit }
    }

    private fun parseWriteEnvelope(body: ByteArray): WriteEnvelope {
        val root = try {
            json.parseToJsonElement(body.decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw GuildException.InvalidResponse
        val code = root.strictInt("code") ?: throw GuildException.InvalidResponse
        return WriteEnvelope(code, root["data"])
    }

    private suspend fun refreshAfterAuthenticationFailure(): Boolean {
        return try {
            sessionProvider.refreshAuthorizer() != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The original authenticated write/read failure remains authoritative.
            false
        }
    }
}

private data class CurrentIdentity(
    val characterId: String?,
    val uuid: String?,
    val guildIdState: IdState,
)

private sealed interface IdState {
    data object Unknown : IdState
    data object None : IdState
    data class Known(val id: GuildId) : IdState
}

private data class GuildRelation(val masterCharacterId: String?)
private data class PhotoRelation(val guildId: GuildId, val authorUuid: String?)
private data class WriteEnvelope(val code: Int, val data: JsonElement?)

private suspend fun OfficialGuildActionScope.requireCurrent() {
    currentCoroutineContext().ensureActive()
    if (!delegate.isCurrent()) throw GuildException.AuthenticationRequired
}

private fun GuildActionScope.officialFor(
    sessionProvider: RisingStonesSessionProvider,
): OfficialGuildActionScope {
    val official = this as? OfficialGuildActionScope ?: throw GuildException.AuthenticationRequired
    if (official.sessionProvider !== sessionProvider) throw GuildException.AuthenticationRequired
    return official
}

private fun GuildUploadedImage.boundTo(scope: GuildActionScope): BoundGuildUploadedImage {
    val officialScope = scope as? OfficialGuildActionScope ?: throw GuildException.AuthenticationRequired
    val image = this as? BoundGuildUploadedImage ?: throw GuildException.ActionNotEligible
    if (image.actionScope !== officialScope) throw GuildException.ActionNotEligible
    return image
}

private fun requireEligible(eligibility: GuildActionEligibility) {
    if (eligibility != GuildActionEligibility.Eligible) throw GuildException.ActionNotEligible
}

private fun relation(left: String?, right: String?): GuildActionEligibility = when {
    left == null || right == null -> GuildActionEligibility.Unknown
    left == right -> GuildActionEligibility.Eligible
    else -> GuildActionEligibility.Ineligible
}

private fun orEligibility(
    first: GuildActionEligibility,
    second: GuildActionEligibility,
): GuildActionEligibility = when {
    GuildActionEligibility.Eligible in listOf(first, second) -> GuildActionEligibility.Eligible
    first == GuildActionEligibility.Ineligible && second == GuildActionEligibility.Ineligible ->
        GuildActionEligibility.Ineligible
    else -> GuildActionEligibility.Unknown
}

private fun JsonObject.requiredDecimal(name: String): String {
    val primitive = this[name] as? JsonPrimitive ?: throw GuildException.InvalidResponse
    if (primitive is JsonNull) throw GuildException.InvalidResponse
    val value = primitive.contentOrNull ?: throw GuildException.InvalidResponse
    if (value.isEmpty() || value.any { it !in '0'..'9' }) throw GuildException.InvalidResponse
    return value
}

private fun JsonObject.requiredText(name: String): String {
    val primitive = this[name] as? JsonPrimitive ?: throw GuildException.InvalidResponse
    if (!primitive.isString) throw GuildException.InvalidResponse
    return primitive.contentOrNull ?: throw GuildException.InvalidResponse
}

private fun JsonObject.optionalIdentity(name: String): String? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val value = (element as? JsonPrimitive)?.contentOrNull ?: throw GuildException.InvalidResponse
    return value.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
}

private fun JsonObject.requiredPositiveDecimal(name: String): String =
    optionalPositiveDecimal(name) ?: throw GuildException.InvalidResponse

private fun JsonObject.optionalPositiveDecimal(name: String): String? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val value = (element as? JsonPrimitive)?.contentOrNull ?: throw GuildException.InvalidResponse
    if (value.isEmpty() || value.any { it !in '0'..'9' } || value.all { it == '0' }) {
        throw GuildException.InvalidResponse
    }
    return value
}

private fun JsonObject?.guildIdState(name: String): IdState {
    val objectValue = this ?: return IdState.Unknown
    val element = objectValue[name] ?: return IdState.Unknown
    if (element is JsonNull) return IdState.Unknown
    val value = (element as? JsonPrimitive)?.contentOrNull ?: throw GuildException.InvalidResponse
    if (value.isEmpty() || value.any { it !in '0'..'9' }) throw GuildException.InvalidResponse
    return if (value.all { it == '0' }) IdState.None else IdState.Known(GuildId(value))
}

private fun JsonObject.requiredPositiveInt(name: String): Int {
    val primitive = this[name] as? JsonPrimitive ?: throw GuildException.InvalidResponse
    return primitive.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GuildException.InvalidResponse
}

private fun JsonObject.strictInt(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.intOrNull
}

private fun query(name: String, value: Any): RisingStonesApiQueryItem =
    RisingStonesApiQueryItem(name, value.toString())

private fun List<Pair<String, String>>.formBody(): ByteArray =
    joinToString("&") { (name, value) -> "${name.formEncoded()}=${value.formEncoded()}" }
        .encodeToByteArray()

private fun String.formEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun Throwable.serverStatus(): Int? = generateSequence(this) { it.cause }
    .filterIsInstance<RisingStonesHttpException.ServerResponse>()
    .firstOrNull()
    ?.statusCode

private const val FormContentType = "application/x-www-form-urlencoded; charset=utf-8"
private const val LabelsPath = "api/home/guild/getGuildLabelList"
private const val CharacterInfoPath = "api/home/groupAndRole/getCharacterBindInfo"
private const val GuildInfoPath = "api/home/guild/getGuildInfo"
private const val PhotoDetailPath = "api/home/guild/getGuildPhotoDetail"
private const val SetGuildInfoPath = "api/home/guild/setGuildInfo"
private const val UploadGuildPhotoPath = "api/home/guild/uploadGuildPhoto"
private const val LikePhotoPath = "api/home/guild/likePhoto"
private const val CommentPhotoPath = "api/home/guild/commentPhoto"
private const val DeleteCommentPath = "api/home/guild/deleteComment"
private const val DeleteGuildPhotoPath = "api/home/guild/deleteGuildPhoto"

private val AuthenticationFailureCodes = setOf(401, 403, 10001, 10003, 10004, 10005, 10403)
