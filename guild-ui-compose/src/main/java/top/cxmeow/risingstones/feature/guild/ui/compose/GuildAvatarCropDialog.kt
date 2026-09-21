package top.cxmeow.risingstones.feature.guild.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput

@Composable
internal fun GuildAvatarCropDialog(
    image: GuildImageUploadInput,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (GuildAvatarCropSelection) -> Unit,
) {
    var centerX by rememberSaveable(image) { mutableFloatStateOf(0.5f) }
    var centerY by rememberSaveable(image) { mutableFloatStateOf(0.5f) }
    var zoom by rememberSaveable(image) { mutableFloatStateOf(1f) }
    var decodeFailed by androidx.compose.runtime.remember(image) { androidx.compose.runtime.mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, image) {
        value = withContext(Dispatchers.Default) { runCatching { decodeGuildImage(image, 1_024) }.getOrNull() }
        decodeFailed = value == null
    }
    val selection = GuildAvatarCropSelection(centerX, centerY, zoom)
    val currentSelection by rememberUpdatedState(selection)
    val description = stringResource(R.string.guild_crop_avatar)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(description) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmap?.let { decoded ->
                    fun update(value: GuildAvatarCropSelection) {
                        val bounded = value.constrainedTo(decoded.width, decoded.height)
                        centerX = bounded.centerX
                        centerY = bounded.centerY
                        zoom = bounded.zoom
                    }
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("guild-avatar-crop-preview")
                        .semantics { contentDescription = description }
                        .pointerInput(decoded, busy) {
                            if (!busy) detectTransformGestures { _, pan, scale, _ ->
                                val old = currentSelection
                                val side = old.rectangle(decoded.width, decoded.height).side
                                update(old.copy(
                                    centerX = old.centerX - pan.x * side / size.width / decoded.width,
                                    centerY = old.centerY - pan.y * side / size.height / decoded.height,
                                    zoom = old.zoom * scale,
                                ))
                            }
                        }) {
                        val rect = selection.rectangle(decoded.width, decoded.height)
                        drawImage(decoded.asImageBitmap(),
                            srcOffset = IntOffset(rect.left, rect.top), srcSize = IntSize(rect.side, rect.side),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    }
                    Text(stringResource(R.string.guild_crop_avatar_hint))
                    val zoomLabel = stringResource(R.string.guild_crop_zoom)
                    Text(zoomLabel)
                    Slider(zoom, { update(selection.copy(zoom = it)) }, enabled = !busy, valueRange = 1f..4f,
                        modifier = Modifier.testTag("guild-avatar-crop-zoom").semantics { contentDescription = zoomLabel })
                    val horizontal = stringResource(R.string.guild_crop_horizontal)
                    Text(horizontal)
                    Slider(centerX, { update(selection.copy(centerX = it)) }, enabled = !busy,
                        modifier = Modifier.testTag("guild-avatar-crop-horizontal").semantics { contentDescription = horizontal })
                    val vertical = stringResource(R.string.guild_crop_vertical)
                    Text(vertical)
                    Slider(centerY, { update(selection.copy(centerY = it)) }, enabled = !busy,
                        modifier = Modifier.testTag("guild-avatar-crop-vertical").semantics { contentDescription = vertical })
                }
                if (decodeFailed) Text(stringResource(R.string.guild_image_unreadable))
                else if (bitmap == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selection) }, enabled = !busy && bitmap != null,
                modifier = Modifier.testTag("guild-confirm-avatar-crop")) { Text(stringResource(R.string.guild_confirm_crop)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy,
                modifier = Modifier.testTag("guild-cancel-avatar-crop")) { Text(stringResource(R.string.guild_cancel)) }
        },
    )
}
