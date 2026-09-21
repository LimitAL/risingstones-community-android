package top.cxmeow.risingstones.feature.guild.domain

/** A credential-bound operation lifetime. Official implementations reject scopes they did not issue. */
interface GuildActionScope : AutoCloseable {
    /** False means all drafts, uploads, and results associated with this scope must be discarded. */
    suspend fun isCurrent(): Boolean

    override fun close()
}

enum class GuildActionEligibility {
    Unknown,
    Ineligible,
    Eligible,
}

data class GuildGuildActionEligibility(
    val guildId: GuildId,
    val manageGuild: GuildActionEligibility,
    val uploadAlbum: GuildActionEligibility,
)

data class GuildPhotoActionEligibility(
    val photoId: Int,
    val guildId: GuildId?,
    val deletePhoto: GuildActionEligibility,
) {
    init {
        require(photoId > 0)
    }
}

data class GuildCommentActionEligibility(
    val commentId: Int,
    val deleteOwnComment: GuildActionEligibility,
) {
    init {
        require(commentId > 0)
    }
}

data class GuildLabelId(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim() && ',' !in value)
    }

    override fun toString(): String = value
}

data class GuildLabel(
    val id: GuildLabelId,
    val name: String,
) {
    init {
        require(name.isNotBlank())
    }
}

data class GuildActiveTimeRange(
    val startHour: Int,
    val endHour: Int,
) {
    init {
        require(startHour in 0..24)
        require(endHour in 0..24)
    }

    val officialValue: String
        get() = "${startHour.toString().padStart(2, '0')}:00-${endHour.toString().padStart(2, '0')}:00"
}

enum class GuildImagePurpose(
    val officialChannel: String,
    val maximumSelectionCount: Int,
) {
    Avatar("avatar", 1),
    Album("guild", 9),
    Comment("default", 1),
}

/** Owns an immutable copy of user-selected bytes. */
class GuildImageUploadInput(
    bytes: ByteArray,
    val mimeType: String,
) {
    private val content = bytes.copyOf()

    init {
        require(content.isNotEmpty())
        require(content.size <= MaximumGuildImageBytes)
        require(mimeType in SupportedGuildImageMimeTypes)
    }

    val byteCount: Int
        get() = content.size

    fun copyBytes(): ByteArray = content.copyOf()

    companion object {
        const val MaximumGuildImageBytes: Int = 22_020_096

        val SupportedGuildImageMimeTypes: Set<String> = setOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/gif",
            "image/webp",
        )
    }
}

/** Opaque upload result bound to the [GuildActionScope] used by the official implementation. */
interface GuildUploadedImage {
    val url: String
    val purpose: GuildImagePurpose
}

interface GuildImageUploadService {
    val canUploadImages: Boolean
    val canAttemptImageUpload: Boolean

    suspend fun uploadImage(
        scope: GuildActionScope,
        purpose: GuildImagePurpose,
        input: GuildImageUploadInput,
    ): GuildUploadedImage
}

sealed interface GuildInfoUpdate {
    data class Picture(val image: GuildUploadedImage) : GuildInfoUpdate {
        init {
            require(image.purpose == GuildImagePurpose.Avatar)
        }
    }

    data class HousingVisibility(val visible: Boolean) : GuildInfoUpdate

    data class Labels(val ids: List<GuildLabelId>) : GuildInfoUpdate {
        init {
            require(ids.distinct().size == ids.size)
        }
    }

    data class WeekdayActiveTime(val range: GuildActiveTimeRange) : GuildInfoUpdate
    data class WeekendActiveTime(val range: GuildActiveTimeRange) : GuildInfoUpdate

    data class Description(val text: String) : GuildInfoUpdate {
        init {
            require(text.isNotEmpty() && text.length <= MaximumDescriptionLength)
        }
    }

    companion object {
        const val MaximumDescriptionLength: Int = 200
    }
}

enum class GuildPhotoLikeResult {
    Liked,
    Unliked,
}

data class GuildPhotoCommentMention(
    val uuid: String,
    val characterName: String,
) {
    init {
        require(uuid.isNotBlank() && uuid.none { it == '#' || it.isISOControl() })
        require(characterName.isNotBlank() && characterName.none { it == '#' || it.isISOControl() })
    }
}

data class GuildPhotoCommentDraft(
    val photoId: Int,
    val contentHtml: String,
    val mentions: List<GuildPhotoCommentMention> = emptyList(),
    val parentId: Int = 0,
    val rootParentId: Int = 0,
    val commentImage: GuildUploadedImage? = null,
) {
    init {
        require(photoId > 0)
        require(parentId >= 0)
        require(rootParentId >= 0)
        require((parentId == 0) == (rootParentId == 0))
        require(contentHtml.isNotEmpty() || commentImage != null)
        require(commentImage == null || commentImage.purpose == GuildImagePurpose.Comment)
    }

    val commentImageUrl: String?
        get() = commentImage?.url
}

interface GuildActionService {
    /** True only after a successful official GuildWrite action for the active credential. */
    val canPerformAuthenticatedWrites: Boolean

    /** First-use eligibility; this is not proof of a successful write. */
    val canAttemptAuthenticatedWrites: Boolean

    /** Captures the active credential and GuildRead generation for a complete user action. */
    suspend fun beginActionScope(): GuildActionScope

    suspend fun labels(scope: GuildActionScope): List<GuildLabel>

    suspend fun guildEligibility(
        scope: GuildActionScope,
        guildId: GuildId,
    ): GuildGuildActionEligibility

    suspend fun photoEligibility(
        scope: GuildActionScope,
        photoId: Int,
    ): GuildPhotoActionEligibility

    /** Unknown is returned until this service has observed the comment in a verified read response. */
    suspend fun commentEligibility(
        scope: GuildActionScope,
        commentId: Int,
    ): GuildCommentActionEligibility

    suspend fun updateGuildInfo(
        scope: GuildActionScope,
        guildId: GuildId,
        update: GuildInfoUpdate,
    )

    suspend fun togglePhotoLike(
        scope: GuildActionScope,
        photoId: Int,
    ): GuildPhotoLikeResult

    suspend fun commentPhoto(
        scope: GuildActionScope,
        draft: GuildPhotoCommentDraft,
    )

    suspend fun deleteOwnComment(
        scope: GuildActionScope,
        commentId: Int,
    )

    suspend fun registerAlbumPhotos(
        scope: GuildActionScope,
        guildId: GuildId,
        images: List<GuildUploadedImage>,
    )

    suspend fun deletePhoto(
        scope: GuildActionScope,
        photoId: Int,
    )
}
