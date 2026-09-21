package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildService
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoViewModel
import top.cxmeow.risingstones.feature.guild.presentation.GuildPhotoViewModelFactory
import top.cxmeow.risingstones.feature.guild.presentation.GuildUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildViewModel
import top.cxmeow.risingstones.feature.guild.presentation.GuildViewModelFactory
import top.cxmeow.risingstones.feature.guild.ui.compose.RisingStonesGuildAuthorNavigation
import top.cxmeow.risingstones.feature.guild.ui.compose.RisingStonesGuildPhotoScreen
import top.cxmeow.risingstones.feature.guild.ui.compose.RisingStonesGuildScreen

@Composable
fun PublishedGuildScreen(service: GuildService, modifier: Modifier = Modifier) {
    RisingStonesGuildAuthorNavigation(onOpenAuthor = {}) {
        RisingStonesGuildScreen(service, onNavigateBack = {}, modifier = modifier,
            onOpenActivity = {}, canOpenActivity = { it > 0 })
    }
}

@Composable
fun PublishedGuildPhotoScreen(service: GuildService, id: Int, modifier: Modifier = Modifier) {
    RisingStonesGuildAuthorNavigation(onOpenAuthor = null) {
        RisingStonesGuildPhotoScreen(service, id, onNavigateBack = {}, modifier = modifier)
    }
}

fun publishedGuildUiState(model: GuildViewModel, photo: GuildPhotoViewModel): Pair<GuildUiState, GuildPhotoUiState> =
    model.state.value to photo.state.value

fun publishedGuildFactories(service: GuildService, id: Int): Pair<ViewModelProvider.Factory, ViewModelProvider.Factory> =
    GuildViewModelFactory(service) to GuildPhotoViewModelFactory(service, id)

@Composable
fun PublishedGuildActions(
    service: GuildService,
    actions: top.cxmeow.risingstones.feature.guild.domain.GuildActionService,
    images: top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadService,
) {
    top.cxmeow.risingstones.feature.guild.ui.compose.RisingStonesGuildActionProvider(actions, images) {
        RisingStonesGuildScreen(service, onNavigateBack = {})
    }
}

fun publishedGuildActionState(
    model: top.cxmeow.risingstones.feature.guild.presentation.GuildActionViewModel,
    source: top.cxmeow.risingstones.feature.guild.presentation.GuildAlbumImageSource,
): top.cxmeow.risingstones.feature.guild.presentation.GuildActionUiState {
    model.setAlbumImageSources(listOf(source))
    model.openComment(top.cxmeow.risingstones.feature.guild.presentation.GuildCommentTarget.TopLevel)
    return model.state.value
}
