package top.cxmeow.risingstones.feature.recruitment.ui.compose

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

private val LocalRecruitmentAuthorNavigation = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Optional community-profile navigation. The default screen API and standalone behavior stay unchanged. */
@Composable
fun RisingStonesRecruitmentAuthorNavigation(onOpenAuthor: ((String) -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRecruitmentAuthorNavigation provides onOpenAuthor, content = content)
}

@Composable
internal fun RecruitmentAuthorName(name: String?, target: String?, modifier: Modifier = Modifier) {
    val navigation = LocalRecruitmentAuthorNavigation.current
    val author = target?.takeIf(String::isNotBlank)
    val label = stringResource(R.string.recruitment_open_author)
    val text = name?.takeIf(String::isNotBlank) ?: if (author != null) label else return
    val canOpen = author != null && navigation != null
    Text(text, modifier = if (canOpen) modifier.heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClickLabel = label) { navigation?.invoke(requireNotNull(author)) }
        .wrapContentHeight().testTag("recruitment-author-link") else modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (canOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
