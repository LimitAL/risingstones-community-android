package top.cxmeow.risingstones.feature.guild.data

import top.cxmeow.risingstones.core.auth.RisingStonesCapabilityScope
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildActionScope
import top.cxmeow.risingstones.feature.guild.domain.GuildUploadedImage

/** Internal bridge shared by the action and image-upload implementations. */
internal class OfficialGuildActionScope(
    internal val delegate: RisingStonesCapabilityScope,
    internal val sessionProvider: RisingStonesSessionProvider,
) : GuildActionScope {
    override suspend fun isCurrent(): Boolean = delegate.isCurrent()

    override fun close() = delegate.close()
}

/** Only the official uploader can produce an image accepted by a later guild mutation. */
internal interface BoundGuildUploadedImage : GuildUploadedImage {
    val actionScope: OfficialGuildActionScope
}
