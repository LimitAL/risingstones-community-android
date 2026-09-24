package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.compose.AsyncImage
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.*

internal data class DynamicActionRuntime(val model: DynamicActionViewModel, val state: DynamicActionUiState, val chooseImage: () -> Unit)

@Composable
internal fun rememberDynamicActionRuntime(key: String): DynamicActionRuntime? {
    val services = LocalDynamicActionServices.current ?: return null
    val model: DynamicActionViewModel = viewModel(key = "dynamic-actions-${System.identityHashCode(services.actions)}-$key",
        factory = remember(services) { DynamicActionViewModelFactory(services.actions, services.images) })
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var pickerTicket by rememberSaveable { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val ticket = pickerTicket
        pickerTicket = null
        if (uri != null && ticket != null) {
            model.acceptImagePicker(ticket) { readDynamicImage(context, uri) }
        }
    }
    return DynamicActionRuntime(model, state) {
        model.beginImagePicker()?.let { ticket ->
            pickerTicket = ticket
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

@Composable
internal fun DynamicInteractionHost(runtime: DynamicActionRuntime?, dynamicId: Int?, comments: List<DynamicComment>,
    replies: Map<Int, List<DynamicComment>>, refresh: () -> Unit, deleted: () -> Unit,
    liked: (DynamicLikeResult) -> Unit) {
    if (runtime == null) return
    val (model, state) = runtime
    LaunchedEffect(dynamicId) { model.bind(dynamicId) }
    SideEffect(model::synchronizeAccess)
    val visibleIds = remember(comments, replies) {
        (comments.map { it.id } + replies.values.flatten().map { it.id }).toSet()
    }
    val replyRoots = remember(replies) {
        replies.flatMap { (root, values) -> values.map { it.id to root } }.toMap()
    }
    LaunchedEffect(state.dynamicId, visibleIds, replyRoots) {
        if (state.dynamicId != null) model.bindCommentEligibility(visibleIds, replyRoots)
    }
    val pendingEvent = state.pendingEvents.firstOrNull()
    LaunchedEffect(pendingEvent?.id, dynamicId) {
        pendingEvent?.let { envelope ->
            when (val event = dynamicId?.let { model.takeEvent(envelope.id, it) }) {
            is DynamicActionEvent.LikeChanged -> liked(event.result)
            DynamicActionEvent.CommentsChanged -> refresh()
            DynamicActionEvent.DynamicDeleted -> deleted()
            null -> Unit
            }
        }
    }
    if (state.editorOpen) DynamicCommentDialog(runtime)
    state.confirmDeleteCommentId?.let { ConfirmDelete(R.string.dynamic_delete_comment_title, R.string.dynamic_delete_comment_message, "dynamic-confirm-delete-comment", model::cancelDeleteComment, model::confirmDeleteComment) }
    if (state.confirmDeleteDynamic) ConfirmDelete(R.string.dynamic_delete_dynamic_title, R.string.dynamic_delete_dynamic_message, "dynamic-confirm-delete-dynamic", model::cancelDeleteDynamic, model::confirmDeleteDynamic)
}

@Composable
internal fun DynamicInteractionPanel(runtime: DynamicActionRuntime, detail: DynamicEntry?) {
    val (model, state) = runtime
    if (state.dynamicId == null) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = model::toggleLike, enabled = !state.isLiking, modifier = Modifier.testTag("dynamic-like")) {
            val likedNow = state.likeResult?.let { it == DynamicLikeResult.Liked } ?: (detail?.isLiked == true)
            Text(stringResource(if (likedNow) R.string.dynamic_unlike else R.string.dynamic_like))
        }
        Button(onClick = model::openComment, modifier = Modifier.testTag("dynamic-comment")) { Text(stringResource(R.string.dynamic_write_comment)) }
        if (state.deleteDynamicEligible) TextButton(onClick = model::requestDeleteDynamic,
            enabled = !state.isDeletingDynamic) { Text(stringResource(R.string.dynamic_delete_dynamic)) }
    }
    state.error?.let { AssistChip(onClick = model::clearError, label = { Text(stringResource(it.messageResource())) }) }
}

@Composable
internal fun DynamicCommentActions(runtime: DynamicActionRuntime, comment: DynamicComment, rootId: Int = comment.id) {
    if (runtime.state.dynamicId == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { runtime.model.openReply(comment.id, rootId, comment.author.name) }) { Text(stringResource(R.string.dynamic_reply)) }
        if (comment.id in runtime.state.deleteComments) TextButton(
            onClick = { runtime.model.requestDeleteComment(comment.id) },
            enabled = comment.id !in runtime.state.deletingCommentIds,
        ) { Text(stringResource(R.string.dynamic_delete_comment)) }
    }
}

@Composable private fun DynamicCommentDialog(runtime: DynamicActionRuntime) {
    val state = runtime.state
    val model = runtime.model
    AlertDialog(onDismissRequest = model::cancelEditor,
        title = { Text(state.replyTarget?.authorName?.let { stringResource(R.string.dynamic_reply_to, it) } ?: stringResource(R.string.dynamic_write_comment)) },
        text = { Column(Modifier.widthIn(max = 640.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DynamicNativeEditor(state.editor, !state.isSubmitting, onChange = model::updateEditor)
            Row {
                TextButton(onClick = { model.setEmojiPicker(!state.editor.emojiPickerOpen) }, enabled = !state.isSubmitting) {
                    Text(stringResource(R.string.dynamic_emoji))
                }
                TextButton(onClick = { model.setMentionPicker(!state.editor.mentionPickerOpen) }, enabled = !state.isSubmitting) {
                    Text(stringResource(R.string.dynamic_mention))
                }
                if (model.canUpload) TextButton(onClick = runtime.chooseImage, enabled = !state.isSubmitting) {
                    Text(stringResource(R.string.dynamic_choose_image))
                }
            }
            if (state.editor.emojiPickerOpen) LazyRow(Modifier.testTag("dynamic-emoji-picker"), horizontalArrangement = Arrangement.spacedBy(4.dp)) { items((1..46).toList()) { number -> AsyncImage(model = dynamicEmojiUrl(number), contentDescription = stringResource(R.string.dynamic_emoji_number, number), modifier = Modifier.size(44.dp).clickable { model.insertEmoji(number) }) } }
            if (state.editor.mentionPickerOpen) LazyRow(Modifier.testTag("dynamic-mention-picker")) {
                items(state.mentionCandidates, key = { it.uuid }) { mention ->
                    AssistChip(onClick = { model.selectMention(mention) }, label = { Text(mention.characterName) })
                }
                if (state.mentionHasMore) item {
                    TextButton(onClick = { model.loadMentions() }) { Text(stringResource(R.string.dynamic_more)) }
                }
            }
            state.image?.let { input ->
                val bitmap by produceState<Bitmap?>(null, input) {
                    value = withContext(Dispatchers.Default) { decodeDynamicImage(input, 1024) }
                }
                bitmap?.let {
                    Image(it.asImageBitmap(), stringResource(R.string.dynamic_selected_image),
                        Modifier.fillMaxWidth().heightIn(max = 220.dp))
                }
                TextButton(onClick = model::removeImage) { Text(stringResource(R.string.dynamic_remove_image)) }
            }
            if (state.isReadingImage || state.isSubmitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(stringResource(it.messageResource()), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("dynamic-comment-error"))
            }
        } },
        dismissButton = { TextButton(onClick = model::cancelEditor, enabled = !state.isSubmitting, modifier = Modifier.testTag("dynamic-cancel-comment")) { Text(stringResource(R.string.dynamic_cancel)) } },
        confirmButton = { Button(onClick = model::submit, enabled = !state.isSubmitting && !state.isReadingImage, modifier = Modifier.testTag("dynamic-submit-comment")) { Text(stringResource(R.string.dynamic_send)) } })
}

@Composable private fun ConfirmDelete(title: Int, message: Int, tag: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(message)) }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.dynamic_cancel)) } }, confirmButton = { Button(onClick = confirm, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.dynamic_delete)) } })

private fun DynamicActionError.messageResource() = when (this) {
    DynamicActionError.AuthenticationRequired -> R.string.dynamic_action_auth
    DynamicActionError.Unavailable -> R.string.dynamic_action_unavailable
    DynamicActionError.InvalidInput -> R.string.dynamic_comment_invalid
    DynamicActionError.InvalidImage -> R.string.dynamic_image_invalid
    DynamicActionError.UploadFailed -> R.string.dynamic_upload_failed
    DynamicActionError.Failed -> R.string.dynamic_action_failed
}
