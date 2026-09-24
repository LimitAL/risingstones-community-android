package top.cxmeow.risingstones.feature.dynamic.ui.compose

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.*

/** A native publishing screen whose retained ViewModel belongs to the host's current destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesDynamicPublishingScreen(
    actionService: DynamicActionService,
    publishingService: DynamicPublishingService,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageUploadService: DynamicImageUploadService? = null,
    relayPostId: Int? = null,
    relayPostTitle: String? = null,
    onPublished: () -> Unit = {},
) {
    val model: DynamicPublishingViewModel = viewModel(
        key = "dynamic-publishing-${System.identityHashCode(actionService)}-$relayPostId",
        factory = remember(actionService, publishingService, imageUploadService, relayPostId, relayPostTitle) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = DynamicPublishingViewModel(
                    actionService, publishingService, imageUploadService, relayPostId, relayPostTitle,
                ) as T
            }
        },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var pickerTicket by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(DynamicPublishingViewModel.MaximumImages)) { uris ->
        val ticket = pickerTicket
        pickerTicket = null
        if (ticket != null) model.acceptImagePicker(ticket, uris.map { LocalPublishingImageSource(context, it) })
    }
    val back = { if (model.requestClose()) onNavigateBack() }
    BackHandler(onBack = back)
    LaunchedEffect(model) { model.initialize() }
    SideEffect(model::synchronizeAccess)
    val publishedId = state.completedId
    LaunchedEffect(publishedId) {
        if (publishedId != null && model.takePublishedResult(publishedId)) {
            onPublished()
            onNavigateBack()
        }
    }
    val editable = state.usable && !state.isSubmitting && state.completedId == null
    Scaffold(modifier.testTag("dynamic-publishing-screen"), topBar = {
        TopAppBar(
            title = { Text(stringResource(if (relayPostId == null) R.string.dynamic_publish_new else R.string.dynamic_publish_relay)) },
            navigationIcon = { TextButton(onClick = back, modifier = Modifier.testTag("dynamic-publish-back")) {
                Text(stringResource(R.string.dynamic_back))
            } },
            actions = { Button(onClick = model::submit, enabled = editable,
                modifier = Modifier.padding(end = 12.dp).testTag("dynamic-publish-submit")) {
                Text(stringResource(R.string.dynamic_publish_send))
            } },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.usable && state.relayPostId != null) Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("dynamic-publish-source")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.dynamic_publish_source), style = MaterialTheme.typography.labelLarge)
                        Text(state.relayPostTitle?.takeIf(String::isNotBlank) ?: stringResource(R.string.dynamic_publish_source_fallback))
                    }
                }
                if (state.usable) {
                    DynamicNativeEditor(state.editor, editable,
                        placeholder = stringResource(R.string.dynamic_publish_hint), onChange = model::updateEditor)
                    if (relayPostId != null) Text(stringResource(R.string.dynamic_publish_relay_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.setEmojiPicker(!state.editor.emojiPickerOpen) }, enabled = editable,
                            modifier = Modifier.testTag("dynamic-publish-emoji")) { Text(stringResource(R.string.dynamic_emoji)) }
                        TextButton(onClick = { model.setMentionPicker(!state.editor.mentionPickerOpen) }, enabled = editable,
                            modifier = Modifier.testTag("dynamic-publish-mention")) { Text(stringResource(R.string.dynamic_mention)) }
                        if (model.canUpload) TextButton(onClick = {
                            model.beginImagePicker()?.let { ticket ->
                                pickerTicket = ticket
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }, enabled = editable && state.images.size < DynamicPublishingViewModel.MaximumImages,
                            modifier = Modifier.testTag("dynamic-publish-images")) {
                            Text(stringResource(R.string.dynamic_publish_choose_images, state.images.size))
                        }
                    }
                    if (state.editor.emojiPickerOpen) LazyVerticalGrid(GridCells.Adaptive(48.dp),
                        Modifier.fillMaxWidth().height(192.dp).testTag("dynamic-publish-emoji-picker")) {
                        items((1..46).toList()) { number ->
                            AsyncImage(dynamicEmojiUrl(number), stringResource(R.string.dynamic_emoji_number, number),
                                modifier = Modifier.size(48.dp).padding(4.dp).clickable(enabled = editable) { model.insertEmoji(number) })
                        }
                    }
                    if (state.editor.mentionPickerOpen) {
                        if (state.isLoadingMentions) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (state.mentionCandidates.isEmpty()) Text(stringResource(R.string.dynamic_publish_no_mentions))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("dynamic-publish-mention-picker")) {
                            items(state.mentionCandidates, key = { it.uuid }) { mention ->
                                AssistChip(onClick = { model.selectMention(mention) }, enabled = editable,
                                    label = { Text(mention.characterName) })
                            }
                            if (state.mentionHasMore) item { TextButton(onClick = { model.loadMentions() },
                                enabled = editable && !state.isLoadingMentions) { Text(stringResource(R.string.dynamic_more)) } }
                        }
                    }
                    Text(stringResource(R.string.dynamic_publish_visibility), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DynamicVisibility.entries.forEach { visibility ->
                            FilterChip(selected = state.visibility == visibility,
                                onClick = { model.setVisibility(visibility) }, enabled = editable,
                                modifier = Modifier.testTag("dynamic-publish-visibility-${visibility.name}"),
                                label = { Text(stringResource(visibility.label())) })
                        }
                    }
                    if (state.images.isNotEmpty()) {
                        Text(stringResource(R.string.dynamic_publish_image_order), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            state.images.forEachIndexed { index, item -> PublishingImageCard(item, index, state.images.size, editable, model) }
                        }
                    }
                }
                if (state.isSubmitting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.uploadingImageIndex?.let { stringResource(R.string.dynamic_publish_uploading, it, state.images.size) }
                        ?: stringResource(R.string.dynamic_publish_submitting))
                }
                state.error?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("dynamic-publish-error")) }
            }
        }
    }
    if (state.confirmDiscard) AlertDialog(
        onDismissRequest = model::cancelDiscard,
        title = { Text(stringResource(R.string.dynamic_publish_discard_title)) },
        text = { Text(stringResource(R.string.dynamic_publish_discard_message)) },
        dismissButton = { TextButton(onClick = model::cancelDiscard) { Text(stringResource(R.string.dynamic_publish_keep_editing)) } },
        confirmButton = { Button(onClick = { model.discard(); onNavigateBack() },
            modifier = Modifier.testTag("dynamic-publish-confirm-discard")) { Text(stringResource(R.string.dynamic_publish_discard)) } },
    )
}

@Composable
private fun PublishingImageCard(item: DynamicPublishingImage, index: Int, count: Int, enabled: Boolean, model: DynamicPublishingViewModel) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Card(Modifier.width(136.dp).testTag("dynamic-publish-image-${item.id}")) {
        val uri = (item.source as? LocalPublishingImageSource)?.uri
        if (uri != null) AsyncImage(uri, stringResource(R.string.dynamic_publish_image_number, index + 1),
            modifier = Modifier.size(136.dp), contentScale = ContentScale.Crop)
        else Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.dynamic_publish_image_number, index + 1)) }
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.testTag("dynamic-publish-image-options-${item.id}")) {
                Text(stringResource(R.string.dynamic_publish_image_options, index + 1))
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dynamic_publish_move_earlier)) },
                    enabled = index > 0, onClick = { expanded = false; model.moveImage(item.id, -1) },
                    modifier = Modifier.testTag("dynamic-publish-move-earlier-${item.id}"))
                DropdownMenuItem(text = { Text(stringResource(R.string.dynamic_publish_move_later)) },
                    enabled = index < count - 1, onClick = { expanded = false; model.moveImage(item.id, 1) },
                    modifier = Modifier.testTag("dynamic-publish-move-later-${item.id}"))
                DropdownMenuItem(text = { Text(stringResource(R.string.dynamic_remove_image)) },
                    onClick = { expanded = false; model.removeImage(item.id) },
                    modifier = Modifier.testTag("dynamic-publish-remove-${item.id}"))
            }
        }
    }
}

private class LocalPublishingImageSource(context: Context, val uri: Uri) : DynamicPublishingImageSource {
    private val applicationContext = context.applicationContext
    override suspend fun read(): DynamicImageUploadInput = readDynamicImage(applicationContext, uri)
}

internal fun DynamicVisibility.label() = when (this) {
    DynamicVisibility.Public -> R.string.dynamic_publish_public
    DynamicVisibility.MutualFollowers -> R.string.dynamic_publish_mutual
    DynamicVisibility.OnlyMe -> R.string.dynamic_publish_only_me
}

internal fun DynamicPublishingError.label() = when (this) {
    DynamicPublishingError.ContentRequired -> R.string.dynamic_publish_content_required
    DynamicPublishingError.TooManyImages -> R.string.dynamic_publish_too_many_images
    DynamicPublishingError.InvalidImage -> R.string.dynamic_image_invalid
    DynamicPublishingError.UploadFailed -> R.string.dynamic_upload_failed
    DynamicPublishingError.AuthenticationRequired -> R.string.dynamic_action_auth
    DynamicPublishingError.Unavailable -> R.string.dynamic_action_unavailable
    DynamicPublishingError.Failed -> R.string.dynamic_action_failed
}
