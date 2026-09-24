package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

private val LocalDynamicPublishingNavigation = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun RisingStonesDynamicPublishingNavigation(
    onCreate: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDynamicPublishingNavigation provides onCreate, content = content)
}

@Composable
internal fun DynamicCreateAction() {
    val onCreate = LocalDynamicPublishingNavigation.current ?: return
    TextButton(
        onClick = onCreate,
        modifier = Modifier.testTag("dynamic-publish-new"),
    ) {
        Text(stringResource(R.string.dynamic_publish_new))
    }
}
