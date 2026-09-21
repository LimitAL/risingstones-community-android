package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumCommentEditorState
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import top.cxmeow.risingstones.feature.forum.ui.compose.RisingStonesForumAuthorNavigation

@Composable
fun PublishedForumAuthorNavigation() {
    RisingStonesForumAuthorNavigation(null) { }
}

fun publishedForumEditorState(model: OfficialForumDetailViewModel): StateFlow<OfficialForumCommentEditorState> =
    model.commentEditorState
