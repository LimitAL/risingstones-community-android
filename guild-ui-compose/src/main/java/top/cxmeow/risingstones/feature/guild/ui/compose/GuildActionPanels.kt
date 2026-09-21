package top.cxmeow.risingstones.feature.guild.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cxmeow.risingstones.feature.guild.domain.GuildActiveTimeRange
import top.cxmeow.risingstones.feature.guild.domain.GuildImagePurpose
import top.cxmeow.risingstones.feature.guild.domain.GuildImageUploadInput
import top.cxmeow.risingstones.feature.guild.domain.GuildInfo
import top.cxmeow.risingstones.feature.guild.domain.GuildInfoUpdate
import top.cxmeow.risingstones.feature.guild.presentation.GuildActionError
import top.cxmeow.risingstones.feature.guild.presentation.GuildActionUiState
import top.cxmeow.risingstones.feature.guild.presentation.GuildActionViewModel
import top.cxmeow.risingstones.feature.guild.presentation.GuildActionViewModelFactory

internal data class GuildActionRuntime(
    val model: GuildActionViewModel,
    val state: GuildActionUiState,
    val importer: GuildImageImportViewModel,
    val launchers: GuildImageLaunchers,
)

@Composable
internal fun rememberGuildActionRuntime(key: String): GuildActionRuntime? {
    val services = LocalGuildActionServices.current ?: return null
    val model: GuildActionViewModel = viewModel(
        key = "guild-actions-${System.identityHashCode(services.actions)}-$key",
        factory = remember(services) { GuildActionViewModelFactory(services.actions, services.images) },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val importer: GuildImageImportViewModel = viewModel(
        key = "guild-image-import-${System.identityHashCode(services.actions)}-$key",
    )
    val launchers = rememberGuildImageLaunchers(model, importer)
    return GuildActionRuntime(model, state, importer, launchers)
}

internal data class GuildImageLaunchers(
    val avatar: () -> Unit,
    val album: () -> Unit,
    val comment: () -> Unit,
)

@Composable
internal fun rememberGuildImageLaunchers(
    model: GuildActionViewModel,
    importer: GuildImageImportViewModel,
): GuildImageLaunchers {
    val context = LocalContext.current.applicationContext
    val currentModel by rememberUpdatedState(model)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val purpose = importer.acceptPickerResult(currentModel)
        if (uri != null && purpose in setOf(GuildImagePurpose.Avatar, GuildImagePurpose.Comment)) {
            importer.read(currentModel, requireNotNull(purpose), listOf(uri)) { readGuildImage(context, it) }
        }
    }
    val album = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(GuildImagePurpose.Album.maximumSelectionCount),
    ) { uris ->
        val purpose = importer.acceptPickerResult(currentModel)
        if (purpose == GuildImagePurpose.Album && uris.isNotEmpty()) {
            importer.selectAlbumSources(currentModel, context, uris)
        }
    }
    return GuildImageLaunchers(
        avatar = {
            if (importer.beginPicker(model, GuildImagePurpose.Avatar)) {
                single.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        },
        album = {
            if (importer.beginPicker(model, GuildImagePurpose.Album)) {
                album.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        },
        comment = {
            if (importer.beginPicker(model, GuildImagePurpose.Comment)) {
                single.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        },
    )
}

@Composable
internal fun GuildActionOverlays(
    model: GuildActionViewModel,
    state: GuildActionUiState,
    importer: GuildImageImportViewModel,
    info: GuildInfo?,
    photoId: Int?,
    launchers: GuildImageLaunchers,
) {
    val imageState by importer.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.draftRevision) { importer.synchronizeDraft(state.draftRevision) }
    if (state.isManagerOpen && info != null) {
        GuildManagerDialog(model, state, info, launchers.avatar)
    }
    imageState.pendingAvatar?.let { pending ->
        GuildAvatarCropDialog(pending, imageState.isReading, importer::cancelAvatarCrop) {
            importer.confirmAvatarCrop(model, it)
        }
    }
    if (state.selectedAlbumImageCount > 0) {
        GuildAlbumUploadDialog(model, state, imageState.albumPreviewUris)
    }
    if (state.commentTarget != null && photoId != null) {
        GuildCommentDialog(model, state, photoId, launchers.comment)
    }
    state.confirmDeleteCommentId?.let {
        GuildConfirmDialog(
            title = stringResource(R.string.guild_delete_comment_title),
            message = stringResource(R.string.guild_delete_comment_message),
            confirmTag = "guild-confirm-delete-comment",
            onDismiss = model::cancelDeleteComment,
            onConfirm = model::confirmDeleteComment,
        )
    }
    state.confirmDeletePhotoId?.let {
        GuildConfirmDialog(
            title = stringResource(R.string.guild_delete_photo_title),
            message = stringResource(R.string.guild_delete_photo_message),
            confirmTag = "guild-confirm-delete-photo",
            onDismiss = model::cancelDeletePhoto,
            onConfirm = model::confirmDeletePhoto,
        )
    }
    imageState.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = importer::clearError,
            title = { Text(stringResource(R.string.guild_image_error_title)) },
            text = { Text(stringResource(failure.messageResource())) },
            confirmButton = { TextButton(onClick = importer::clearError) { Text(stringResource(R.string.guild_close)) } },
        )
    }
}

@Composable
private fun GuildManagerDialog(
    model: GuildActionViewModel,
    state: GuildActionUiState,
    info: GuildInfo,
    chooseAvatar: () -> Unit,
) {
    var visible by rememberSaveable(info.id.value, info.housing.visibility) {
        mutableStateOf(info.housing.visibility == top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility.Visible)
    }
    var weekdayStart by rememberSaveable(info.id.value) { mutableStateOf(info.weekdayActiveTime.hourPart(0)) }
    var weekdayEnd by rememberSaveable(info.id.value) { mutableStateOf(info.weekdayActiveTime.hourPart(1)) }
    var weekendStart by rememberSaveable(info.id.value) { mutableStateOf(info.weekendActiveTime.hourPart(0)) }
    var weekendEnd by rememberSaveable(info.id.value) { mutableStateOf(info.weekendActiveTime.hourPart(1)) }
    val selectedLabels = rememberSaveable(info.id.value, saver = listSaver(
        save = { labels: androidx.compose.runtime.snapshots.SnapshotStateList<top.cxmeow.risingstones.feature.guild.domain.GuildLabelId> -> labels.map { it.value } },
        restore = { values -> values.map { top.cxmeow.risingstones.feature.guild.domain.GuildLabelId(it) }.toMutableStateList() },
    )) { mutableStateListOf<top.cxmeow.risingstones.feature.guild.domain.GuildLabelId>() }
    var labelsInitialized by rememberSaveable(info.id.value) { mutableStateOf(false) }
    LaunchedEffect(state.labels, info.labels, state.isLoadingLabels) {
        if (!labelsInitialized && !state.isLoadingLabels && state.labels.isNotEmpty()) {
            selectedLabels += state.labels.filter { it.name in info.labels }.map { it.id }
            labelsInitialized = true
        }
    }
    val busy = state.isSavingGuildInfo
    AlertDialog(
        modifier = Modifier.widthIn(max = 720.dp),
        onDismissRequest = model::closeManager,
        title = { Text(stringResource(R.string.guild_manage_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.guild_manage_avatar), style = MaterialTheme.typography.titleMedium)
                state.avatarDraft?.let { GuildLocalImagePreview(it, stringResource(R.string.guild_manage_avatar)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = chooseAvatar, enabled = !busy && model.canUploadImages) {
                        Text(stringResource(R.string.guild_choose_image))
                    }
                    if (state.avatarDraft != null) Button(onClick = model::saveAvatar, enabled = !busy) {
                        Text(stringResource(R.string.guild_upload_avatar))
                    }
                }
                Text(stringResource(R.string.guild_manage_housing), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(if (visible) R.string.guild_housing_public else R.string.guild_housing_hidden))
                    Switch(visible, { visible = it }, enabled = !busy)
                }
                Button(onClick = { model.saveGuildInfo(GuildInfoUpdate.HousingVisibility(visible)) }, enabled = !busy) {
                    Text(stringResource(R.string.guild_save_housing))
                }
                Text(stringResource(R.string.guild_manage_labels), style = MaterialTheme.typography.titleMedium)
                if (state.isLoadingLabels) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.labels.forEach { label ->
                    FilterChip(
                        selected = label.id in selectedLabels,
                        onClick = { if (label.id in selectedLabels) selectedLabels.remove(label.id) else selectedLabels += label.id },
                        label = { Text(label.name) },
                        enabled = !busy,
                    )
                }
                Button(onClick = { model.saveGuildInfo(GuildInfoUpdate.Labels(selectedLabels.toList())) }, enabled = !busy) {
                    Text(stringResource(R.string.guild_save_labels))
                }
                Text(stringResource(R.string.guild_manage_weekday), style = MaterialTheme.typography.titleMedium)
                GuildTimeFields(weekdayStart, weekdayEnd, { weekdayStart = it }, { weekdayEnd = it }, !busy)
                Button(onClick = {
                    parseRange(weekdayStart, weekdayEnd)?.let {
                        model.saveGuildInfo(GuildInfoUpdate.WeekdayActiveTime(it))
                    }
                }, enabled = !busy && parseRange(weekdayStart, weekdayEnd) != null) {
                    Text(stringResource(R.string.guild_save_weekday))
                }
                Text(stringResource(R.string.guild_manage_weekend), style = MaterialTheme.typography.titleMedium)
                GuildTimeFields(weekendStart, weekendEnd, { weekendStart = it }, { weekendEnd = it }, !busy)
                Button(onClick = {
                    parseRange(weekendStart, weekendEnd)?.let {
                        model.saveGuildInfo(GuildInfoUpdate.WeekendActiveTime(it))
                    }
                }, enabled = !busy && parseRange(weekendStart, weekendEnd) != null) {
                    Text(stringResource(R.string.guild_save_weekend))
                }
                Text(stringResource(R.string.guild_manage_description), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.managerDescription,
                    onValueChange = model::updateManagerDescription,
                    modifier = Modifier.fillMaxWidth().testTag("guild-description-draft"),
                    enabled = !busy,
                    supportingText = { Text("${state.managerDescription.length}/${GuildInfoUpdate.MaximumDescriptionLength}") },
                )
                Button(onClick = {
                    runCatching { GuildInfoUpdate.Description(state.managerDescription) }
                        .onSuccess(model::saveGuildInfo)
                }, enabled = !busy && state.managerDescription.isNotEmpty()) {
                    Text(stringResource(R.string.guild_save_description))
                }
                state.error?.let { GuildActionErrorText(it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = model::closeManager, enabled = !busy) { Text(stringResource(R.string.guild_close)) }
        },
    )
}

@Composable
private fun GuildTimeFields(
    start: String,
    end: String,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(start, { if (it.length <= 2) onStart(it.filter(Char::isDigit)) },
            Modifier.weight(1f), label = { Text(stringResource(R.string.guild_start_hour)) }, enabled = enabled)
        OutlinedTextField(end, { if (it.length <= 2) onEnd(it.filter(Char::isDigit)) },
            Modifier.weight(1f), label = { Text(stringResource(R.string.guild_end_hour)) }, enabled = enabled)
    }
}

@Composable
private fun GuildAlbumUploadDialog(
    model: GuildActionViewModel,
    state: GuildActionUiState,
    previewUris: List<android.net.Uri>,
) {
    AlertDialog(
        onDismissRequest = model::clearAlbumDraft,
        title = { Text(stringResource(R.string.guild_album_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.guild_album_selected_count, state.selectedAlbumImageCount))
                previewUris.firstOrNull()?.let { uri ->
                    coil3.compose.AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.guild_album_upload_title),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (previewUris.isEmpty()) state.selectedAlbumImages.firstOrNull()?.let {
                    GuildLocalImagePreview(it, stringResource(R.string.guild_album_upload_title))
                }
                if (state.isUploadingAlbum) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.guild_album_upload_progress,
                        state.uploadedAlbumCount, state.selectedAlbumImageCount))
                }
                state.error?.let { GuildActionErrorText(it) }
            }
        },
        confirmButton = {
            Button(onClick = model::uploadAlbum, enabled = !state.isUploadingAlbum,
                modifier = Modifier.testTag("guild-confirm-album-upload")) {
                Text(stringResource(R.string.guild_upload))
            }
        },
        dismissButton = {
            TextButton(onClick = model::clearAlbumDraft, enabled = !state.isUploadingAlbum) {
                Text(stringResource(R.string.guild_discard_draft))
            }
        },
    )
}

@Composable
private fun GuildCommentDialog(
    model: GuildActionViewModel,
    state: GuildActionUiState,
    photoId: Int,
    chooseImage: () -> Unit,
) {
    val busy = state.isSubmittingComment
    AlertDialog(
        modifier = Modifier.widthIn(max = 640.dp),
        onDismissRequest = { model.closeComment(keepDraft = true) },
        title = {
            Text(state.commentTarget?.authorName?.let { stringResource(R.string.guild_reply_to, it) }
                ?: stringResource(R.string.guild_write_comment))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.commentText,
                    onValueChange = model::updateCommentText,
                    modifier = Modifier.fillMaxWidth().testTag("guild-comment-draft"),
                    enabled = !busy,
                    minLines = 3,
                )
                state.commentImage?.let {
                    GuildLocalImagePreview(it, stringResource(R.string.guild_comment_image))
                    TextButton(onClick = { model.setCommentImage(null) }, enabled = !busy) {
                        Text(stringResource(R.string.guild_remove_image))
                    }
                }
                if (model.canUploadImages) TextButton(onClick = chooseImage, enabled = !busy) {
                    Text(stringResource(R.string.guild_choose_image))
                }
                state.error?.let { GuildActionErrorText(it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { model.submitComment(photoId) },
                enabled = !busy && (state.commentText.isNotBlank() || state.commentImage != null),
                modifier = Modifier.testTag("guild-submit-comment")) {
                Text(stringResource(R.string.guild_send_comment))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { model.closeComment(keepDraft = true) }, enabled = !busy) {
                    Text(stringResource(R.string.guild_keep_draft))
                }
                TextButton(onClick = { model.closeComment(keepDraft = false) }, enabled = !busy) {
                    Text(stringResource(R.string.guild_discard_draft))
                }
            }
        },
    )
}

@Composable
private fun GuildConfirmDialog(
    title: String,
    message: String,
    confirmTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                Text(stringResource(R.string.guild_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.guild_cancel)) } },
    )
}

@Composable
private fun GuildLocalImagePreview(input: GuildImageUploadInput, description: String, square: Boolean = false) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, input) {
        value = withContext(Dispatchers.Default) { runCatching { decodeGuildImage(input, 1_024) }.getOrNull() }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (square) 240.dp else 120.dp, max = 280.dp),
            contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
        )
    }
}

@Composable
internal fun GuildActionErrorText(error: GuildActionError) {
    Text(stringResource(when (error) {
        GuildActionError.AuthenticationRequired -> R.string.guild_action_auth_required
        GuildActionError.Unavailable -> R.string.guild_action_unavailable
        GuildActionError.InvalidInput -> R.string.guild_action_invalid
        GuildActionError.UploadFailed -> R.string.guild_upload_failed
        GuildActionError.Failed -> R.string.guild_action_failed
    }), color = MaterialTheme.colorScheme.error)
}

private fun GuildImageReadFailure.messageResource(): Int = when (this) {
    GuildImageReadFailure.Unsupported -> R.string.guild_image_unsupported
    GuildImageReadFailure.TooLarge -> R.string.guild_image_too_large
    GuildImageReadFailure.Unreadable -> R.string.guild_image_unreadable
    GuildImageReadFailure.TooMany -> R.string.guild_image_too_many
}

private fun String?.hourPart(index: Int): String = this
    ?.split('-')
    ?.getOrNull(index)
    ?.substringBefore(':')
    ?.toIntOrNull()
    ?.toString()
    ?: "0"

private fun parseRange(start: String, end: String): GuildActiveTimeRange? = runCatching {
    GuildActiveTimeRange(start.toInt(), end.toInt())
}.getOrNull()
