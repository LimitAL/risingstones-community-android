package top.cxmeow.risingstones.feature.forum.ui.compose

import android.graphics.BitmapFactory
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVote
import top.cxmeow.risingstones.feature.forum.domain.deadline
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailInteractionState
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumInteractionError
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumLoadStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ForumCommentComposer(
    interaction: OfficialForumDetailInteractionState,
    model: OfficialForumDetailViewModel,
    isSubmitting: Boolean,
    importer: ForumCommentImageImportViewModel,
) {
    val context = LocalContext.current
    val editor by model.commentEditorState.collectAsStateWithLifecycle()
    val canMention = model.canMention
    val hasPrivateEditorState = editor.mentions.isNotEmpty() || editor.candidates.isNotEmpty() ||
        editor.isMentionPickerOpen || editor.candidateStatus != OfficialForumLoadStatus.Idle ||
        editor.candidateQuery.isNotEmpty() || editor.candidateError != null
    LaunchedEffect(model, canMention, hasPrivateEditorState) {
        if (!canMention && hasPrivateEditorState) model.clearProtectedContent()
    }
    // A read capability can disappear independently of write access. Hide private content immediately.
    if (!canMention && hasPrivateEditorState) return
    val imageRead by importer.state.collectAsStateWithLifecycle()
    val onPicked by rememberUpdatedState<(android.net.Uri?) -> Unit> { uri ->
        val currentDraft = importer.acceptPickerResult(model)
        if (uri != null && currentDraft && !isSubmitting) {
            val applicationContext = context.applicationContext
            importer.readImage(model) {
                readForumCommentImage(applicationContext, uri)
            }
        }
    }
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    var picker by remember(registry, importer) {
        mutableStateOf<ActivityResultLauncher<PickVisualMediaRequest>?>(null)
    }
    DisposableEffect(registry, importer) {
        val launcher = registry.register(importer.registrationKey, ActivityResultContracts.PickVisualMedia()) {
            onPicked(it)
        }
        picker = launcher
        onDispose { launcher.unregister(); picker = null }
    }
    val busy = isSubmitting || imageRead.isReading
    AlertDialog(
        modifier = Modifier.widthIn(max = 640.dp).imePadding(),
        onDismissRequest = {
            when {
                editor.isEmojiPickerOpen -> model.closeCommentEmojiPicker()
                editor.isMentionPickerOpen -> model.closeCommentMentionPicker()
                !busy -> model.closeCommentComposer()
            }
        },
        title = { Text(when {
            editor.isEmojiPickerOpen -> stringResource(R.string.forum_comment_emoji)
            editor.isMentionPickerOpen -> stringResource(R.string.forum_comment_mention)
            else -> interaction.replyTarget?.authorName?.let { stringResource(R.string.forum_reply_to, it) }
                ?: stringResource(R.string.forum_write_comment)
        }) },
        text = {
            if (editor.isEmojiPickerOpen) ForumCommentEmojiPicker(model)
            else if (editor.isMentionPickerOpen) ForumCommentMentionPicker(editor, model)
            else Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val dialogView = LocalView.current
                fun hideKeyboard() {
                    (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(dialogView.windowToken, 0)
                }
                ForumCommentTextEditor(editor, !busy, model::updateCommentEditor)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { hideKeyboard(); model.openCommentEmojiPicker() }, enabled = !busy,
                        modifier = Modifier.testTag("forum-open-emoji")) { Text(stringResource(R.string.forum_comment_emoji)) }
                    if (canMention) TextButton(onClick = { hideKeyboard(); model.openCommentMentionPicker() }, enabled = !busy,
                        modifier = Modifier.testTag("forum-open-mention")) { Text(stringResource(R.string.forum_comment_mention)) }
                }
                interaction.commentImage?.let { image ->
                    val preview = remember(image) {
                        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
                    }
                    if (preview != null) Image(preview, stringResource(R.string.forum_comment_image),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit)
                    TextButton(onClick = { model.setCommentImage(null); importer.clearError() }, enabled = !busy) {
                        Text(stringResource(R.string.forum_remove_image))
                    }
                }
                if (model.canAttachCommentImage) {
                    TextButton(onClick = {
                        if (importer.beginPicker(model)) picker?.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                        enabled = !busy && picker != null, modifier = Modifier.testTag("forum-choose-comment-image")) { Text(stringResource(R.string.forum_choose_image)) }
                    Text(stringResource(R.string.forum_image_limits), style = MaterialTheme.typography.bodySmall)
                }
                imageRead.failure?.let { Text(stringResource(when (it) {
                    ForumImageReadFailure.TooLarge -> R.string.forum_image_too_large
                    ForumImageReadFailure.Unsupported -> R.string.forum_image_unsupported
                    ForumImageReadFailure.Unreadable -> R.string.forum_image_read_failed
                }), color = MaterialTheme.colorScheme.error) }
                interaction.commentError?.let { ForumInteractionErrorText(it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = model::discardCommentDraft, enabled = !busy,
                    modifier = Modifier.testTag("forum-discard-comment")) {
                    Text(stringResource(R.string.forum_discard_draft))
                }
            }
        },
        confirmButton = {
            if (!editor.isEmojiPickerOpen && !editor.isMentionPickerOpen) TextButton(onClick = model::submitDraftComment,
                enabled = !busy && model.canInteract &&
                    (interaction.commentText.isNotBlank() || interaction.commentImage != null),
                modifier = Modifier.testTag("forum-submit-comment")) {
                Text(stringResource(R.string.forum_send_comment))
            }
        },
        dismissButton = {
            if (editor.isEmojiPickerOpen || editor.isMentionPickerOpen) TextButton(onClick = {
                model.closeCommentEmojiPicker(); model.closeCommentMentionPicker()
            }, modifier = Modifier.testTag("forum-close-comment-picker")) {
                Text(stringResource(R.string.forum_back_to_comment))
            } else TextButton(onClick = model::closeCommentComposer, enabled = !busy) {
                Text(stringResource(R.string.forum_keep_draft))
            }
        },
    )
}

@Composable
internal fun ForumInteractiveVote(
    vote: OfficialForumPostVote,
    selected: Set<Int>,
    isSubmitting: Boolean,
    canWrite: Boolean,
    resultsAvailable: Boolean,
    error: OfficialForumInteractionError?,
    onSelection: (Set<Int>) -> Unit,
    onSubmit: () -> Unit,
) {
    val maximum = if (vote.allowsMultipleSelection) vote.maximumSelectionCount ?: vote.options.size else 1
    val minimum = if (vote.allowsMultipleSelection) maxOf(vote.minimumSelectionCount ?: 1, 1) else 1
    val deadline = remember(vote.endDateText) { vote.deadline() }
    var expired by remember(deadline) { mutableStateOf(deadline?.let { Instant.now().isAfter(it) } == true) }
    LaunchedEffect(deadline) {
        if (deadline != null && !expired) {
            delay((deadline.toEpochMilli() - System.currentTimeMillis()).coerceIn(0, Long.MAX_VALUE - 1) + 1)
            expired = true
        }
    }
    val showResults = resultsAvailable || vote.hasParticipated
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vote.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (vote.allowsMultipleSelection) R.string.forum_vote_multiple else R.string.forum_vote_single,
                minimum, maximum), style = MaterialTheme.typography.bodySmall)
            deadline?.let { Text(stringResource(R.string.forum_vote_deadline,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it)),
                style = MaterialTheme.typography.bodySmall) }
            if (vote.levelRequirement > 0) Text(stringResource(R.string.forum_vote_level, vote.levelRequirement),
                style = MaterialTheme.typography.bodySmall)
            vote.options.forEach { option ->
                val checked = if (vote.hasParticipated) option.isParticipant else option.optionId in selected
                val enabled = canWrite && !isSubmitting && !showResults && !expired &&
                    (!vote.allowsMultipleSelection || checked || selected.size < maximum)
                val toggle = {
                    onSelection(if (vote.allowsMultipleSelection) {
                        if (checked) selected - option.optionId else selected + option.optionId
                    } else setOf(option.optionId))
                }
                Row(Modifier.fillMaxWidth().testTag("forum-vote-option-${vote.id}-${option.optionId}")
                    .selectable(selected = checked, enabled = enabled,
                        role = if (vote.allowsMultipleSelection) Role.Checkbox else Role.RadioButton, onClick = toggle)) {
                    if (vote.allowsMultipleSelection) Checkbox(checked, onCheckedChange = null, enabled = enabled)
                    else RadioButton(checked, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (vote.type == 2) {
                            RisingStonesRemoteImage(option.title,
                                contentDescription = option.description ?: stringResource(R.string.forum_vote_picture),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 180.dp),
                                contentScale = ContentScale.Fit,
                                fallback = { Text(stringResource(R.string.forum_image_failed)) })
                            option.description?.let { Text(it) }
                        } else Text(option.title)
                        if (showResults) Text(stringResource(R.string.forum_vote_count, option.totalVoteCount),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (vote.hasParticipated) Text(stringResource(R.string.forum_vote_participated))
            else if (expired) Text(stringResource(R.string.forum_vote_closed))
            else if (showResults) Text(stringResource(R.string.forum_vote_results))
            else if (canWrite) Button(onClick = onSubmit,
                enabled = !isSubmitting && selected.size in minimum..maximum,
                modifier = Modifier.testTag("forum-submit-vote-${vote.id}")) {
                Text(stringResource(R.string.forum_vote_submit))
            }
            if (isSubmitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { ForumInteractionErrorText(it) }
        }
    }
}

@Composable
internal fun ForumInteractionErrorText(error: OfficialForumInteractionError) {
    Text(stringResource(when (error) {
        OfficialForumInteractionError.Unavailable -> R.string.forum_write_unavailable
        OfficialForumInteractionError.AuthenticationRequired -> R.string.forum_auth_required
        OfficialForumInteractionError.InvalidInput -> R.string.forum_invalid_input
        OfficialForumInteractionError.ImageUploadFailed -> R.string.forum_image_upload_failed
        OfficialForumInteractionError.Failed -> R.string.forum_action_failed
    }), color = MaterialTheme.colorScheme.error)
}
