package top.cxmeow.risingstones.feature.forum.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.core.auth.RisingStonesIdentityConflictResolver
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import java.time.Instant

enum class OfficialForumContentKind(val wireValue: Int) { Post(1), Guide(2) }

enum class OfficialForumSearchOrder(val wireValue: String) { Time("time"), Comment("comment") }

enum class OfficialForumCommentOrder(val wireValue: String) {
    Hottest("hottest"), Latest("latest"), Earliest("earliest"),
}

data class OfficialForumListQuery(
    val contentKind: OfficialForumContentKind = OfficialForumContentKind.Post,
    val page: Int = 1,
    val limit: Int = 20,
    val partIds: List<Int> = emptyList(),
)

data class OfficialForumSearchQuery(
    val contentKind: OfficialForumContentKind = OfficialForumContentKind.Post,
    val keywords: String,
    val partIds: List<Int> = emptyList(),
    val order: OfficialForumSearchOrder = OfficialForumSearchOrder.Time,
    val page: Int = 1,
    val limit: Int = 20,
)

data class OfficialForumCommentQuery(
    val postId: Int,
    val page: Int = 1,
    val limit: Int = 20,
    val order: OfficialForumCommentOrder = OfficialForumCommentOrder.Hottest,
    val onlyPostAuthor: Boolean = false,
)

data class OfficialForumSubCommentQuery(
    val rootParentId: Int,
    val page: Int = 1,
    val limit: Int = 20,
    val order: OfficialForumCommentOrder = OfficialForumCommentOrder.Earliest,
)

data class OfficialForumPage<T>(val items: List<T>, val total: Int, val page: Int)

data class OfficialForumPartFilter(val id: Int, val name: String, val weight: Int)

data class OfficialForumAuthor(
    val uuid: String,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
    val adminTag: Int,
) {
    val locationText: String
        get() = listOf(groupName, areaName).map(String::trim).filter(String::isNotEmpty)
            .joinToString(" / ")
}

data class OfficialForumPart(val id: Int?, val name: String, val parentName: String)

data class OfficialForumPostSummary(
    val id: Int,
    val title: String,
    val excerpt: String,
    val author: OfficialForumAuthor,
    val part: OfficialForumPart,
    val coverImageUrls: List<String>,
    val createdAt: Instant?,
    val lastCommentAt: Instant?,
    val commentCount: Int,
    val likeCount: Int,
    val starCount: Int,
    val readCount: Int,
    val isTop: Boolean,
    val isRefined: Boolean,
)

sealed interface OfficialForumRichTextSegment {
    data class Text(val value: String) : OfficialForumRichTextSegment
    data class Emoji(val number: Int) : OfficialForumRichTextSegment
    data class Link(val text: String, val url: String) : OfficialForumRichTextSegment
    data class Image(val url: String, val width: Int?, val height: Int?) : OfficialForumRichTextSegment
}

/**
 * The post body keeps its HTML structure instead of turning every image into an
 * attachment.  This mirrors the Apple client's block renderer: images that
 * share a paragraph with text are inline; standalone media and richer HTML
 * constructs receive their own block.
 */
sealed interface OfficialForumPostBodyBlock {
    data class Paragraph(val segments: List<OfficialForumRichTextSegment>) : OfficialForumPostBodyBlock
    data class Image(val url: String, val width: Int?, val height: Int?) : OfficialForumPostBodyBlock
    data class VideoEmbed(val url: String, val isBilibili: Boolean) : OfficialForumPostBodyBlock
    data class Table(val rows: List<List<List<OfficialForumRichTextSegment>>>) : OfficialForumPostBodyBlock
    data class Disclosure(
        val title: List<OfficialForumRichTextSegment>,
        val blocks: List<OfficialForumPostBodyBlock>,
        val initiallyExpanded: Boolean,
    ) : OfficialForumPostBodyBlock
    data class Callout(val segments: List<OfficialForumRichTextSegment>) : OfficialForumPostBodyBlock
    data object Divider : OfficialForumPostBodyBlock
}

data class OfficialForumPostDetail(
    val id: Int,
    val title: String,
    val bodyHtml: String,
    val bodyText: String,
    val bodySegments: List<OfficialForumRichTextSegment>,
    val bodyBlocks: List<OfficialForumPostBodyBlock>,
    val contentImageUrls: List<String>,
    val votes: List<OfficialForumPostVote>,
    val author: OfficialForumAuthor,
    val part: OfficialForumPart,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val lastCommentAt: Instant?,
    val commentCount: Int,
    val likeCount: Int,
    val starCount: Int,
    val isLiked: Boolean?,
    val isStarred: Boolean?,
    val readCount: Int,
    val ipLocation: String?,
    val isTop: Boolean,
    val isRefined: Boolean,
)

data class OfficialForumPostVote(
    val id: String,
    val title: String,
    val type: Int,
    val minimumSelectionCount: Int?,
    val maximumSelectionCount: Int?,
    val levelRequirement: Int,
    val endDateText: String?,
    val totalUserCount: Int,
    val options: List<OfficialForumPostVoteOption>,
) {
    val allowsMultipleSelection: Boolean get() = type != 1 || (maximumSelectionCount ?: 1) > 1
    val hasParticipated: Boolean get() = options.any(OfficialForumPostVoteOption::isParticipant)
}

data class OfficialForumPostVoteOption(
    val id: String,
    val optionId: Int,
    val title: String,
    val description: String?,
    val type: Int,
    val totalVoteCount: Int,
    val isParticipant: Boolean,
)

data class OfficialForumComment(
    val id: Int,
    val author: OfficialForumAuthor,
    val replyToAuthorName: String?,
    val bodyText: String,
    val bodySegments: List<OfficialForumRichTextSegment>,
    val imageUrls: List<String>,
    val createdAt: Instant?,
    val ipLocation: String?,
    val likeCount: Int,
    val childCount: Int,
    val childPreviewComments: List<OfficialForumComment> = emptyList(),
    val isPostAuthor: Boolean,
    val isMine: Boolean,
)

data class OfficialForumCommentDraft(
    val postId: Int,
    val parentId: Int,
    val rootParentId: Int,
    val contentHtml: String,
    val commentPictureText: String,
)

/** A normalized, single comment image. Upload credentials never leave the data layer. */
data class OfficialForumCommentImageUpload(
    val bytes: ByteArray,
    val mimeType: String,
)

data class OfficialForumVoteSelection(val optionId: Int, val title: String)

data class OfficialForumVoteDraft(
    val postId: Int,
    val options: List<OfficialForumVoteSelection>,
)

data class OfficialForumVoteResult(
    val voteTotalUser: Int,
    val voteDetails: Map<Int, Int>,
)

data class OfficialForumIdentityConflictState(
    val isPresented: Boolean = false,
    val isReclaiming: Boolean = false,
)

interface OfficialForumIdentityConflictHandler : RisingStonesIdentityConflictResolver {
    val identityConflictState: StateFlow<OfficialForumIdentityConflictState>
    override suspend fun awaitIdentityConflictResolution(): RisingStonesRequestAuthorizer?
    suspend fun reclaimIdentityConflict()
    suspend fun cancelIdentityConflict()
}

private val EmptyOfficialForumIdentityConflictState =
    MutableStateFlow(OfficialForumIdentityConflictState())

interface OfficialForumService {
    val canPerformAuthenticatedWrites: Boolean
    val identityConflictState: StateFlow<OfficialForumIdentityConflictState>
        get() = EmptyOfficialForumIdentityConflictState
    suspend fun reclaimIdentityConflict() = Unit
    suspend fun cancelIdentityConflict() = Unit
    suspend fun fetchParts(): List<OfficialForumPartFilter>
    suspend fun fetchPosts(query: OfficialForumListQuery): OfficialForumPage<OfficialForumPostSummary>
    suspend fun searchPosts(query: OfficialForumSearchQuery): OfficialForumPage<OfficialForumPostSummary>
    suspend fun fetchPostDetail(id: Int): OfficialForumPostDetail
    suspend fun fetchComments(query: OfficialForumCommentQuery): OfficialForumPage<OfficialForumComment>
    suspend fun fetchSubComments(
        query: OfficialForumSubCommentQuery,
    ): OfficialForumPage<OfficialForumComment>
    suspend fun likePost(id: Int): Int
    suspend fun starPost(id: Int): Int
    suspend fun uploadCommentImage(image: OfficialForumCommentImageUpload): String =
        throw OfficialForumException.ImageUploadFailed
    suspend fun submitComment(draft: OfficialForumCommentDraft): List<Int>
    suspend fun deleteComment(id: Int)
    suspend fun submitVote(draft: OfficialForumVoteDraft): OfficialForumVoteResult
}

sealed class OfficialForumException(message: String) : Exception(message) {
    class Business(val code: Int?, val detail: String?) :
        OfficialForumException("Official forum API failed: ${detail ?: code ?: "unknown"}")

    data object MissingPayload : OfficialForumException("Official forum response is incomplete")
    data object AuthenticationRequired : OfficialForumException("Rising Stones authentication is required")
    data object ImageUploadFailed : OfficialForumException("Official forum image upload failed")
    data object IdentityConflict :
        OfficialForumException("Rising Stones identity is active on another device")
}
