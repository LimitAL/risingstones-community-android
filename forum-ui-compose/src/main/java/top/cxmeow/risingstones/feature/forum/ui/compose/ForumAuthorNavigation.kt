package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumAuthor

private val LocalForumAuthorNavigation = compositionLocalOf<((String) -> Unit)?> { null }

/** Optional community profile navigation using the author's official community UUID. */
@Composable
fun RisingStonesForumAuthorNavigation(onOpenAuthor: ((String) -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalForumAuthorNavigation provides onOpenAuthor, content = content)
}

@Composable
internal fun ForumAuthorIdentity(
    author: OfficialForumAuthor,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val onOpen = LocalForumAuthorNavigation.current
    val label = stringResource(R.string.forum_open_author_profile, author.characterName)
    val navigation = if (onOpen != null && author.uuid.isNotBlank()) {
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = label) { onOpen(author.uuid) }
            .semantics(mergeDescendants = true) { contentDescription = label }
    } else Modifier
    Row(modifier.then(navigation).testTag("forum-author-${author.uuid}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
