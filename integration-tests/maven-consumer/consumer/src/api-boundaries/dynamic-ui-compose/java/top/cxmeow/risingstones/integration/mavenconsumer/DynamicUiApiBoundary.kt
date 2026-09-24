package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicAuthorNavigation

@Composable
fun PublishedDynamicAuthorNavigation() {
    RisingStonesDynamicAuthorNavigation(null) { }
}

@Composable
fun PublishedDynamicPublishingNavigation(onCreate: (() -> Unit)?, content: @Composable () -> Unit) {
    top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicPublishingNavigation(
        onCreate,
        content,
    )
}

@Composable
fun PublishedDynamicDetail(model: top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel) {
    top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicDetailScreen(model, {})
}

@Composable
fun PublishedDynamicActions(
    model: top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel,
    actions: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionService,
    images: top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadService,
) {
    top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicActionProvider(actions, images) {
        top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicDetailScreen(model, {})
    }
}

@Composable
fun PublishedDynamicPublishingScreen(
    actions: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionService,
    publishing: top.cxmeow.risingstones.feature.dynamic.domain.DynamicPublishingService,
    images: top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadService?,
    relayPostId: Int?,
    relayPostTitle: String?,
    onNavigateBack: () -> Unit,
    onPublished: () -> Unit,
) {
    top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicPublishingScreen(
        actionService = actions,
        publishingService = publishing,
        imageUploadService = images,
        relayPostId = relayPostId,
        relayPostTitle = relayPostTitle,
        onNavigateBack = onNavigateBack,
        onPublished = onPublished,
    )
}

@Composable
fun PublishedDynamicRecruitmentRelayScreen(
    actions: top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionService,
    relay: top.cxmeow.risingstones.feature.dynamic.domain.DynamicRecruitmentRelayService,
    recruitmentId: Int,
    origin: top.cxmeow.risingstones.feature.dynamic.domain.DynamicOrigin,
    sourceTitle: String,
    onNavigateBack: () -> Unit,
    modifier: androidx.compose.ui.Modifier,
    onPublished: () -> Unit,
) {
    top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicRecruitmentRelayScreen(
        actionService = actions,
        relayService = relay,
        recruitmentId = recruitmentId,
        origin = origin,
        sourceTitle = sourceTitle,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        onPublished = onPublished,
    )
}
