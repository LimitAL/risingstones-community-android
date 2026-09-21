package top.cxmeow.risingstones.feature.message.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.message.domain.MessageAuthorTarget

private val LocalMessageAuthorNavigation = staticCompositionLocalOf<((MessageAuthorTarget) -> Unit)?> { null }

/** Optional community-profile navigation. The default screen API and standalone behavior stay unchanged. */
@Composable
fun RisingStonesMessageAuthorNavigation(onOpenAuthor: ((MessageAuthorTarget) -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMessageAuthorNavigation provides onOpenAuthor, content = content)
}

@Composable
internal fun MessageAuthorName(name: String?, target: MessageAuthorTarget?, modifier: Modifier = Modifier) {
    val navigation = LocalMessageAuthorNavigation.current
    val author = target?.takeIf { it is MessageAuthorTarget.Self || (it is MessageAuthorTarget.Community && it.uuid.isNotBlank()) }
    val label = stringResource(R.string.message_open_author)
    val text = if (author == MessageAuthorTarget.Self) stringResource(R.string.message_author_self)
        else name?.takeIf(String::isNotBlank) ?: if (author != null) label else return
    val canOpen = author != null && navigation != null
    Text(text, modifier = if (canOpen) modifier.heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClickLabel = label) { navigation?.invoke(requireNotNull(author)) }
        .wrapContentHeight().testTag("message-author-link") else modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (canOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
