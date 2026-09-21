package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoViewModel
import top.cxmeow.risingstones.feature.guild.presentation.GuildUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildViewModel

/** The aggregate declares only guild-ui-compose and receives these contracts through public APIs. */
class PublishedGuildArtifactsSmoke(
    val service: GuildService,
    val model: GuildViewModel,
    val photoModel: GuildPhotoViewModel,
) {
    fun currentStates(): Pair<GuildUiState, GuildPhotoUiState> = model.state.value to photoModel.state.value

    suspend fun currentGuildId(): GuildId? = when (val own = service.ownGuild()) {
        OwnGuild.None -> null
        is OwnGuild.Joined -> own.guildId
    }
}
