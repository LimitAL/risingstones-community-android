package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor

private val LocalGlamourAuthorNavigation = compositionLocalOf<((String) -> Unit)?> { null }

/** Optional community profile navigation, independent of the existing author works navigation. */
@Composable
fun RisingStonesGlamourAuthorNavigation(onOpenAuthor: ((String) -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlamourAuthorNavigation provides onOpenAuthor, content = content)
}

@Composable
internal fun GlamourCommunityProfileLink(author: GlamourAuthor) {
    val onOpen = LocalGlamourAuthorNavigation.current ?: return
    val uuid = author.id?.takeIf(String::isNotBlank) ?: return
    val label = stringResource(R.string.glamour_open_author_profile, author.characterName)
    TextButton(onClick = { onOpen(uuid) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .testTag("glamour-community-author-$uuid").semantics { contentDescription = label }) {
        Text(stringResource(R.string.glamour_community_profile))
    }
}
