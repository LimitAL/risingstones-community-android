package top.cxmeow.risingstones.feature.guild.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import top.cxmeow.risingstones.feature.guild.domain.GuildActionService
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService

internal data class GuildActionServices(
    val actions: GuildActionService,
    val images: GuildImageUploadService?,
)

internal val LocalGuildActionServices = staticCompositionLocalOf<GuildActionServices?> { null }

/** Adds optional write actions to the read-only guild screens without changing their service contract. */
@Composable
fun RisingStonesGuildActionProvider(
    actionService: GuildActionService?,
    imageUploadService: GuildImageUploadService? = null,
    content: @Composable () -> Unit,
) {
    val services = remember(actionService, imageUploadService) {
        actionService?.let { GuildActionServices(it, imageUploadService) }
    }
    CompositionLocalProvider(LocalGuildActionServices provides services, content = content)
}
