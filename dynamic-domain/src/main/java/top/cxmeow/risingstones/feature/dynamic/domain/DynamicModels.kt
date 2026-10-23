package top.cxmeow.risingstones.feature.dynamic.domain

import java.time.Instant

data class DynamicAuthor(
    val id: String,
    val name: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
)

enum class DynamicOrigin(val wireValue: Int) {
    Original(1), Post(2), Guide(3), Dynamic(4), BeginnerRecruitment(5), DutyRecruitment(6),
    GuildRecruitment(7), RolePlayRecruitment(8), OtherRecruitment(9), Glamour(10), Unknown(0),
}

data class DynamicReference(
    val origin: DynamicOrigin,
    val id: String,
    val title: String,
    val description: String,
    val imageUrls: List<String>,
)

data class DynamicEntry(
    val id: Int,
    val author: DynamicAuthor,
    val contentHtml: String,
    val imageUrls: List<String>,
    val createdAt: Instant?,
    val commentCount: Int,
    val likeCount: Int,
    val isLiked: Boolean,
    val reference: DynamicReference?,
)

data class DynamicComment(
    val id: Int,
    val author: DynamicAuthor,
    val contentHtml: String,
    val imageUrls: List<String>,
    val createdAt: Instant?,
    val replyToName: String?,
    val likeCount: Int,
    val childCount: Int,
)

data class DynamicPage<T> @JvmOverloads constructor(
    val items: List<T>,
    val page: Int,
    val hasMore: Boolean,
    val pageTime: String? = null,
    /** This endpoint's nonnegative count, or null when absent/invalid; never the loaded row count. */
    val total: Int? = null,
) {
    init { require(total == null || total >= 0) }

    /** Original Kotlin default-constructor descriptor (pageTime was the only default). */
    @Suppress("UNUSED_PARAMETER")
    constructor(items: List<T>, page: Int, hasMore: Boolean, pageTime: String?, mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?) :
        this(items, page, hasMore, if (mask and 8 != 0) null else pageTime, null)

    /** Original JVM copy descriptor preserves the additional source metadata. */
    fun copy(items: List<T>, page: Int, hasMore: Boolean, pageTime: String?): DynamicPage<T> =
        DynamicPage(items, page, hasMore, pageTime, total)

    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun <T> `copy$default`(source: DynamicPage<T>, items: List<T>?, page: Int, hasMore: Boolean,
            pageTime: String?, mask: Int, marker: Any?): DynamicPage<T> =
            source.copy(if (mask and 1 != 0) source.items else requireNotNull(items),
                if (mask and 2 != 0) source.page else page,
                if (mask and 4 != 0) source.hasMore else hasMore,
                if (mask and 8 != 0) source.pageTime else pageTime)
    }
}

data class DynamicListQuery(val page: Int = 1, val limit: Int = 20, val pageTime: String? = null)

interface DynamicService {
    val canRead: Boolean
    suspend fun fetchFeed(query: DynamicListQuery): DynamicPage<DynamicEntry>
    suspend fun fetchDetail(id: Int): DynamicEntry
    suspend fun fetchComments(id: Int, query: DynamicListQuery): DynamicPage<DynamicComment>
    suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery): DynamicPage<DynamicComment>
}

sealed class DynamicException(message: String) : Exception(message) {
    data object AuthenticationRequired : DynamicException("Dynamic authentication required")
    data object Unavailable : DynamicException("Dynamic capability unavailable")
    data object InvalidResponse : DynamicException("Invalid dynamic response")
    data object Network : DynamicException("Dynamic transport failed")
    data object IdentityConflict : DynamicException("Dynamic action identity no longer matches")
    data object ActionNotEligible : DynamicException("Dynamic action is not eligible")
    data object ImageUploadFailed : DynamicException("Dynamic image upload failed")
    class Business(val code: Int?) : DynamicException("Dynamic request failed")
}
