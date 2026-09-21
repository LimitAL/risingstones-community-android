package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicAuthorNavigation

@Composable
fun PublishedDynamicAuthorNavigation() {
    RisingStonesDynamicAuthorNavigation(null) { }
}

@Composable
fun PublishedDynamicDetail(model: top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel) {
    top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicDetailScreen(model, {})
}
