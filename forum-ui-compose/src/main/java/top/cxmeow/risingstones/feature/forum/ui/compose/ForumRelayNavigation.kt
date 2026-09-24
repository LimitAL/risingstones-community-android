package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

private val LocalForumRelayNavigation = staticCompositionLocalOf<((Int, String) -> Unit)?> { null }

@Composable
fun RisingStonesForumRelayNavigation(
    onRelay: ((postId: Int, postTitle: String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalForumRelayNavigation provides onRelay, content = content)
}

@Composable
internal fun ForumRelayAction(postId: Int, postTitle: String) {
    val onRelay = LocalForumRelayNavigation.current ?: return
    TextButton(
        onClick = { onRelay(postId, postTitle) },
        modifier = Modifier.testTag("forum-relay-post"),
    ) {
        Text(stringResource(R.string.forum_relay_dynamic))
    }
}
