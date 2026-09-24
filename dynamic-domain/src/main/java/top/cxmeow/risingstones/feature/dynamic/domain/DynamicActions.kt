package top.cxmeow.risingstones.feature.dynamic.domain

/** A credential-bound lifetime for one explicit dynamic interaction. */
interface DynamicActionScope : AutoCloseable {
    suspend fun isCurrent(): Boolean
    override fun close()
}

enum class DynamicActionEligibility { Unknown, Ineligible, Eligible }

data class DynamicEntryActionEligibility(
    val dynamicId: Int,
    val deleteOwnDynamic: DynamicActionEligibility,
) {
    init { require(dynamicId > 0) }
}

data class DynamicCommentActionEligibility(
    val commentId: Int,
    val deleteOwnComment: DynamicActionEligibility,
) {
    init { require(commentId > 0) }
}

enum class DynamicLikeResult { Liked, Unliked }

data class DynamicCommentMention(
    val uuid: String,
    val characterName: String,
) {
    init {
        require(uuid.isNotBlank() && uuid.none { it == '#' || it.isISOControl() })
        require(characterName.isNotBlank() && characterName.none { it == '#' || it.isISOControl() })
    }
}

/** Owns an immutable copy of one user-selected dynamic image. */
class DynamicImageUploadInput(bytes: ByteArray, val mimeType: String) {
    private val content = bytes.copyOf()

    init {
        require(content.isNotEmpty())
        require(content.size <= MaximumDynamicImageBytes)
        require(mimeType in SupportedDynamicImageMimeTypes)
    }

    val byteCount: Int get() = content.size
    fun copyBytes(): ByteArray = content.copyOf()

    companion object {
        const val MaximumDynamicImageBytes: Int = 22_020_096
        val SupportedDynamicImageMimeTypes: Set<String> = setOf(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp",
        )
    }
}

/** Opaque upload result. Official mutations accept only results bound to the same action scope. */
interface DynamicUploadedImage

enum class DynamicVisibility { Public, MutualFollowers, OnlyMe }

data class DynamicPublishDraft(
    val contentHtml: String,
    val visibility: DynamicVisibility = DynamicVisibility.Public,
    val mentions: List<DynamicCommentMention> = emptyList(),
    val images: List<DynamicUploadedImage> = emptyList(),
) {
    init {
        require(contentHtml.isNotEmpty())
        require(images.size <= MaximumPublishingImageCount)
    }
}

data class DynamicPostRelayDraft(
    val postId: Int,
    val contentHtml: String,
    val visibility: DynamicVisibility = DynamicVisibility.Public,
    val mentions: List<DynamicCommentMention> = emptyList(),
) {
    init { require(postId > 0) }
}

data class DynamicRecruitmentRelayDraft(
    val recruitmentId: Int,
    val origin: DynamicOrigin,
    val visibility: DynamicVisibility = DynamicVisibility.Public,
) {
    init {
        require(recruitmentId > 0)
        require(origin in RecruitmentRelayOrigins)
    }
}

interface DynamicPublishingService {
    suspend fun publish(scope: DynamicActionScope, draft: DynamicPublishDraft)
    suspend fun relayPost(scope: DynamicActionScope, draft: DynamicPostRelayDraft)
}

interface DynamicRecruitmentRelayService {
    suspend fun relayRecruitment(scope: DynamicActionScope, draft: DynamicRecruitmentRelayDraft)
}

interface DynamicPublishingImageUploadService {
    suspend fun uploadPublishingImage(
        scope: DynamicActionScope,
        input: DynamicImageUploadInput,
    ): DynamicUploadedImage
}

interface DynamicImageUploadService {
    val canUploadImages: Boolean
    val canAttemptImageUpload: Boolean

    suspend fun uploadCommentImage(
        scope: DynamicActionScope,
        input: DynamicImageUploadInput,
    ): DynamicUploadedImage
}

data class DynamicCommentDraft(
    val dynamicId: Int,
    val contentHtml: String,
    val mentions: List<DynamicCommentMention> = emptyList(),
    val parentId: Int = 0,
    val rootParentId: Int = 0,
    val commentImage: DynamicUploadedImage? = null,
) {
    init {
        require(dynamicId > 0)
        require(parentId >= 0)
        require(rootParentId >= 0)
        require((parentId == 0) == (rootParentId == 0))
        require(contentHtml.isNotEmpty() || commentImage != null)
    }
}

interface DynamicActionService {
    /** True only after a successful official DynamicWrite action for the active credential. */
    val canPerformAuthenticatedWrites: Boolean

    /** First-use eligibility; this does not prove that a write has succeeded. */
    val canAttemptAuthenticatedWrites: Boolean

    suspend fun beginActionScope(): DynamicActionScope

    suspend fun entryEligibility(
        scope: DynamicActionScope,
        dynamicId: Int,
    ): DynamicEntryActionEligibility

    suspend fun commentEligibility(
        scope: DynamicActionScope,
        commentId: Int,
    ): DynamicCommentActionEligibility

    suspend fun fetchMentionCandidates(
        scope: DynamicActionScope,
        query: DynamicListQuery,
    ): DynamicPage<DynamicCommentMention>

    /** Reads and records comment authors inside this credential-bound scope. */
    suspend fun fetchComments(
        scope: DynamicActionScope,
        dynamicId: Int,
        query: DynamicListQuery,
    ): DynamicPage<DynamicComment>

    /** Reads and records reply authors inside this credential-bound scope. */
    suspend fun fetchReplies(
        scope: DynamicActionScope,
        rootParentId: Int,
        query: DynamicListQuery,
    ): DynamicPage<DynamicComment>

    suspend fun toggleDynamicLike(scope: DynamicActionScope, dynamicId: Int): DynamicLikeResult
    suspend fun comment(scope: DynamicActionScope, draft: DynamicCommentDraft)
    suspend fun deleteOwnComment(scope: DynamicActionScope, commentId: Int)
    suspend fun deleteOwnDynamic(scope: DynamicActionScope, dynamicId: Int)
}

private const val MaximumPublishingImageCount = 9
private val RecruitmentRelayOrigins = setOf(
    DynamicOrigin.BeginnerRecruitment,
    DynamicOrigin.DutyRecruitment,
    DynamicOrigin.GuildRecruitment,
    DynamicOrigin.RolePlayRecruitment,
    DynamicOrigin.OtherRecruitment,
)
