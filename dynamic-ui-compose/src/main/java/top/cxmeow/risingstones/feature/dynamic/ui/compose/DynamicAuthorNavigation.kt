package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicAuthor

private val LocalDynamicAuthorNavigation = compositionLocalOf<((String) -> Unit)?> { null }

/** Optional community profile navigation using the author's official community UUID. */
@Composable
fun RisingStonesDynamicAuthorNavigation(onOpenAuthor: ((String) -> Unit)?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDynamicAuthorNavigation provides onOpenAuthor, content = content)
}

@Composable
internal fun DynamicAuthorIdentity(author: DynamicAuthor) {
    val onOpen = LocalDynamicAuthorNavigation.current
    val name = author.name.ifBlank { stringResource(R.string.dynamic_unknown_author) }
    val label = stringResource(R.string.dynamic_open_author_profile, name)
    val navigation = if (onOpen != null && author.id.isNotBlank()) {
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = label) { onOpen(author.id) }
            .semantics(mergeDescendants = true) { contentDescription = label }
    } else Modifier
    Box(navigation.testTag("dynamic-author-${author.id}"), contentAlignment = Alignment.CenterStart) {
        Text(name, style = MaterialTheme.typography.titleSmall)
    }
}
