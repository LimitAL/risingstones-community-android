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

@Composable
fun PublishedForumRelayNavigation(
    onRelay: ((postId: Int, postTitle: String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    top.cxmeow.risingstones.feature.forum.ui.compose.RisingStonesForumRelayNavigation(onRelay, content)
}

fun publishedForumEditorState(model: OfficialForumDetailViewModel): StateFlow<OfficialForumCommentEditorState> =
    model.commentEditorState

fun publishedForumTextSearchState(
    model: top.cxmeow.risingstones.feature.forum.presentation.OfficialForumListViewModel,
): StateFlow<top.cxmeow.risingstones.feature.forum.presentation.OfficialForumTextSearchUiState> {
    model.setSearchField(top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchField.Body)
    return model.searchState
}
