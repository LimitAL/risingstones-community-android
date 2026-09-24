package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind
import top.cxmeow.risingstones.feature.recruitment.ui.compose.RisingStonesRecruitmentAuthorNavigation
import top.cxmeow.risingstones.feature.recruitment.ui.compose.RisingStonesRecruitmentRelayNavigation

@Composable
fun PublishedRecruitmentAuthorNavigation() {
    RisingStonesRecruitmentAuthorNavigation(null) { }
}

@Composable
fun PublishedRecruitmentRelayNavigation(
    onRelay: ((Int, RecruitmentBoardKind, String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    RisingStonesRecruitmentRelayNavigation(onRelay, content)
}
