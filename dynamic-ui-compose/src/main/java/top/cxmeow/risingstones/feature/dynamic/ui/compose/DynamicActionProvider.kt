package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicActionService
import top.cxmeow.risingstones.feature.dynamic.domain.DynamicImageUploadService

internal data class DynamicActionServices(val actions: DynamicActionService, val images: DynamicImageUploadService?)
internal val LocalDynamicActionServices = staticCompositionLocalOf<DynamicActionServices?> { null }

/** Adds optional explicit dynamic interactions without changing the read-only screen contract. */
@Composable
fun RisingStonesDynamicActionProvider(
    actionService: DynamicActionService?,
    imageUploadService: DynamicImageUploadService? = null,
    content: @Composable () -> Unit,
) {
    val services = remember(actionService, imageUploadService) { actionService?.let { DynamicActionServices(it, imageUploadService) } }
    CompositionLocalProvider(LocalDynamicActionServices provides services, content = content)
}
