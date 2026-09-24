package top.cxmeow.risingstones.feature.recruitment.ui.compose

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

private val LocalRecruitmentRelayNavigation =
    staticCompositionLocalOf<((Int, RecruitmentBoardKind, String) -> Unit)?> { null }

@Composable
fun RisingStonesRecruitmentRelayNavigation(
    onRelay: ((id: Int, board: RecruitmentBoardKind, title: String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRecruitmentRelayNavigation provides onRelay, content = content)
}

@Composable
internal fun RecruitmentRelayAction(id: Int, board: RecruitmentBoardKind, title: String) {
    val onRelay = LocalRecruitmentRelayNavigation.current ?: return
    TextButton(
        onClick = { onRelay(id, board, title) },
        modifier = Modifier.testTag("recruitment-relay-dynamic"),
    ) {
        Text(stringResource(R.string.recruitment_relay_dynamic))
    }
}
