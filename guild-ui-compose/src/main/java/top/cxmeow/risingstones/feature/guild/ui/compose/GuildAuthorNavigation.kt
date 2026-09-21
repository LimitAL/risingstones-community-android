package top.cxmeow.risingstones.feature.guild.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val LocalGuildAuthorNavigation = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Optional navigation from a response-provided community UUID to the host's author page. */
@Composable
fun RisingStonesGuildAuthorNavigation(
    onOpenAuthor: ((String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGuildAuthorNavigation provides onOpenAuthor, content = content)
}

@Composable
internal fun GuildAuthorName(name: String, uuid: String?, modifier: Modifier = Modifier) {
    val onOpen = LocalGuildAuthorNavigation.current
    val target = uuid?.takeIf(String::isNotBlank)
    val enabled = target != null && onOpen != null
    val label = stringResource(R.string.guild_open_author)
    val displayName = name.takeIf(String::isNotBlank) ?: stringResource(R.string.guild_author)
    Text(
        text = displayName,
        modifier = if (enabled) {
            modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = label) { onOpen?.invoke(requireNotNull(target)) }
                .wrapContentHeight()
                .testTag("guild-author-$target")
        } else {
            modifier
        },
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
