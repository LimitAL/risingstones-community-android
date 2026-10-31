package top.cxmeow.risingstones.feature.guild.domain

/** Guild identifiers stay as decimal strings so parsing does not impose an unverified integer range. */
data class GuildId(val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it in '0'..'9' } && value.any { it != '0' })
    }

    override fun toString(): String = value
}

sealed interface OwnGuild {
    data object None : OwnGuild
    data class Joined(val guildId: GuildId) : OwnGuild
}

enum class GuildHousingVisibility { Visible, Private }

/** Private housing values are removed by the data layer before this value is constructed. */
data class GuildHousing(
    val visibility: GuildHousingVisibility,
    val description: String?,
    val remainingDays: String?,
)

data class GuildInfo(
    val id: GuildId,
    val name: String,
    val tag: String,
    val areaName: String,
    val groupName: String,
    val imageUrl: String?,
    val descriptionHtml: String,
    val createdAt: String?,
    val rank: Int?,
    val memberCount: Int,
    val activeMemberCount: Int,
    val grandCompanyName: String,
    val weekdayActiveTime: String?,
    val weekendActiveTime: String?,
    val labels: List<String>,
    val housing: GuildHousing,
)

enum class GuildMemberRegistration { Registered, Unregistered }

data class GuildMember(
    val registration: GuildMemberRegistration,
    /** Null means the official response did not provide a usable community UUID. */
    val authorUuid: String?,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
    val profile: String,
    val relation: Int?,
    val adminTag: Int?,
)

/** 来源数组原始行数；null为缺省或null，0仅表示明确空数组，并非部队总人数。 */
data class GuildMemberSourceRows(val registered: Int?, val unregistered: Int?) {
    init {
        require(registered == null || registered >= 0)
        require(unregistered == null || unregistered >= 0)
    }
}

data class GuildMembers @JvmOverloads constructor(
    val registered: List<GuildMember>,
    val unregistered: List<GuildMember>,
    /** null为旧消费者或手工快照未报告来源；不能从过滤后列表补算。 */
    val sourceRows: GuildMemberSourceRows? = null,
) {
    /** 保留旧两字段copy的JVM描述符，同时保留新增来源事实。 */
    fun copy(registered: List<GuildMember>, unregistered: List<GuildMember>): GuildMembers =
        GuildMembers(registered, unregistered, sourceRows)

    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun `copy$default`(source: GuildMembers, registered: List<GuildMember>?,
            unregistered: List<GuildMember>?, mask: Int, marker: Any?): GuildMembers =
            source.copy(if (mask and 1 != 0) source.registered else requireNotNull(registered),
                if (mask and 2 != 0) source.unregistered else requireNotNull(unregistered))
    }
}

/** Local summary for guild activity navigation; it deliberately does not reuse dynamic-domain. */
data class GuildActivitySummary(
    val id: Int,
    val contentHtml: String,
    val imageUrls: List<String>,
    val createdAt: String?,
    val authorUuid: String?,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
)

data class GuildPhotoSummary(
    val id: Int,
    val photoUrl: String,
    val characterName: String,
    val avatarUrl: String?,
    val commentCount: Int,
    val likeCount: Int,
    val isLiked: Boolean,
)

data class GuildPhotoDetail(
    val id: Int,
    val guildId: GuildId,
    val photoUrl: String,
    /** Missing UUID must disable author navigation rather than fall back to a character ID. */
    val authorUuid: String?,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
    val relayCount: Int,
    val commentCount: Int,
    val likeCount: Int,
    val isLiked: Boolean,
)

data class GuildPhotoComment(
    val id: Int,
    val photoId: Int,
    val parentId: Int,
    val rootParentId: Int,
    val authorUuid: String?,
    val characterName: String,
    val areaName: String,
    val groupName: String,
    val avatarUrl: String?,
    val contentHtml: String,
    val pictureUrl: String?,
    val createdAt: String?,
    val replyToUuid: String?,
    val replyToName: String?,
    val childCount: Int,
)

data class GuildPage<T>(
    val items: List<T>,
    val page: Int,
    val hasMore: Boolean,
    val totalCount: Int? = null,
    val nextPageTime: String? = null,
)

interface GuildService {
    val canRead: Boolean

    suspend fun ownGuild(): OwnGuild
    suspend fun info(guildId: GuildId): GuildInfo
    suspend fun members(guildId: GuildId): GuildMembers
    suspend fun activities(guildId: GuildId, page: Int = 1): GuildPage<GuildActivitySummary>
    suspend fun photos(guildId: GuildId, page: Int = 1): GuildPage<GuildPhotoSummary>
    suspend fun photo(id: Int): GuildPhotoDetail
    suspend fun comments(
        photoId: Int,
        page: Int = 1,
        pageTime: String? = null,
    ): GuildPage<GuildPhotoComment>
    suspend fun replies(rootParentId: Int, page: Int = 1): GuildPage<GuildPhotoComment>
}

sealed class GuildException(message: String) : Exception(message) {
    data object AuthenticationRequired : GuildException("Guild authentication required")
    data object Unavailable : GuildException("Guild read capability unavailable")
    data object ActionNotEligible : GuildException("Guild action is not eligible")
    data object IdentityConflict : GuildException("Guild action identity no longer matches")
    data object ImageUploadFailed : GuildException("Guild image upload failed")
    data object InvalidResponse : GuildException("Invalid guild response")
    data object Network : GuildException("Guild transport failed")
    class Business(val code: Int?) : GuildException("Guild request failed")
}
