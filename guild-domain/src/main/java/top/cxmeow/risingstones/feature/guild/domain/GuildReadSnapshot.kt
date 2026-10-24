package top.cxmeow.risingstones.feature.guild.domain

/** Additive read-only projection: missing source counts and membership are not fabricated zeros. */
data class GuildReadSnapshot(
    val info: GuildInfo, val memberCount: Int?, val activeMemberCount: Int?, val isMember: Boolean,
    val board: String?, val sourceDate: String?, val emblemUrl: String? = null,
)
data class GuildReadPhoto(val photo: GuildPhotoSummary, val authorUuid: String?)
