package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DynamicLayoutMode { Compact, Medium, Expanded }
fun dynamicLayoutMode(widthDp: Int): DynamicLayoutMode = when {
    widthDp < 600 -> DynamicLayoutMode.Compact
    widthDp < 840 -> DynamicLayoutMode.Medium
    else -> DynamicLayoutMode.Expanded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesDynamicScreen(
    viewModel: DynamicViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenReference: ((DynamicReference) -> Unit)? = null,
    canOpenReference: (DynamicReference) -> Boolean = { true },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listScrollState = rememberLazyListState()
    val detailScrollState = key(state.selectedId) { rememberLazyListState() }
    LaunchedEffect(viewModel) { viewModel.ensureLoaded() }
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.dynamic_title)) },
            navigationIcon = { TextButton(onClick = {
                if (state.canNavigateBackInDetail) viewModel.clearSelection() else onNavigateBack()
            }) { Text(stringResource(R.string.dynamic_back)) } },
            actions = { TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.dynamic_refresh)) } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val mode = dynamicLayoutMode(maxWidth.value.toInt())
            BackHandler {
                if (state.canNavigateBackInDetail || (mode == DynamicLayoutMode.Compact && state.selectedId != null)) viewModel.clearSelection()
                else onNavigateBack()
            }
            if (mode == DynamicLayoutMode.Compact) {
                if (state.selectedId == null) DynamicList(state, viewModel, Modifier.fillMaxSize(), listScrollState)
                else DynamicDetail(state, viewModel, onOpenReference, canOpenReference, Modifier.fillMaxSize(), true, detailScrollState)
            } else {
                Row(Modifier.fillMaxSize()) {
                    DynamicList(state, viewModel, Modifier.width(if (mode == DynamicLayoutMode.Medium) 300.dp else 380.dp)
                        .fillMaxHeight(), listScrollState)
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        if (state.selectedId == null) Message(stringResource(R.string.dynamic_select))
                        else DynamicDetail(state, viewModel, onOpenReference, canOpenReference,
                            Modifier.widthIn(max = 840.dp).fillMaxHeight().fillMaxWidth(), false, detailScrollState)
                    }
                }
            }
        }
    }
}

/** Displays a selection supplied by the host without reading the following feed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesDynamicDetailScreen(
    viewModel: DynamicViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenReference: ((DynamicReference) -> Unit)? = null,
    canOpenReference: (DynamicReference) -> Boolean = { true },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = key(state.selectedId) { rememberLazyListState() }
    BackHandler(onBack = onNavigateBack)
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.dynamic_title)) }, navigationIcon = {
            TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.dynamic_back)) }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.status == DynamicLoadStatus.AuthenticationRequired -> Message(stringResource(R.string.dynamic_sign_in))
                state.status == DynamicLoadStatus.Unavailable -> Message(stringResource(R.string.dynamic_unavailable))
                state.selectedId == null -> Message(stringResource(R.string.dynamic_select))
                else -> DynamicDetail(state, viewModel, onOpenReference, canOpenReference,
                    Modifier.widthIn(max = 840.dp).fillMaxSize(), showBack = false, scrollState = scrollState)
            }
        }
    }
}

@Composable
private fun DynamicList(state: DynamicUiState, model: DynamicViewModel, modifier: Modifier, scrollState: LazyListState) {
    Column(modifier) {
        if (state.status == DynamicLoadStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.status == DynamicLoadStatus.AuthenticationRequired -> Message(stringResource(R.string.dynamic_sign_in))
            state.status == DynamicLoadStatus.Unavailable -> Message(stringResource(R.string.dynamic_unavailable))
            state.status == DynamicLoadStatus.Failed && state.items.isEmpty() -> Retry(model::refresh)
            state.status == DynamicLoadStatus.Loaded && state.items.isEmpty() -> Message(stringResource(R.string.dynamic_empty))
            else -> LazyColumn(Modifier.fillMaxSize().testTag("dynamic-list-content"), state = scrollState, contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.status == DynamicLoadStatus.Failed) item { Retry(model::refresh, retained = true) }
                items(state.items, key = DynamicEntry::id) { entry ->
                    Card(onClick = { model.select(entry.id) }, colors = CardDefaults.cardColors(
                        containerColor = if (state.selectedId == entry.id) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer)) {
                        Entry(entry, Modifier.padding(12.dp), preview = true)
                    }
                }
                item {
                    if (state.loadingMore) CircularProgressIndicator()
                    else if (state.loadMoreFailed) Retry(model::loadMore)
                    else if (state.hasMore) TextButton(onClick = model::loadMore) {
                        Text(stringResource(R.string.dynamic_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicDetail(state: DynamicUiState, model: DynamicViewModel,
    onOpenReference: ((DynamicReference) -> Unit)?, canOpenReference: (DynamicReference) -> Boolean,
    modifier: Modifier, showBack: Boolean, scrollState: LazyListState) {
    LazyColumn(modifier.testTag("dynamic-detail-content"), state = scrollState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (showBack) TextButton(onClick = model::clearSelection) { Text(stringResource(R.string.dynamic_back)) }
                TextButton(onClick = model::refreshDetail) { Text(stringResource(R.string.dynamic_refresh)) }
            }
        }
        if (state.detailStatus == DynamicLoadStatus.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.detailStatus == DynamicLoadStatus.Failed) item { Retry(model::refreshDetail, retained = state.detail != null) }
        state.detail?.let { detail ->
            item { Entry(detail, Modifier.fillMaxWidth(), false) }
            detail.reference?.let { reference ->
                if (onOpenReference != null && canOpenReference(reference) && reference.origin != DynamicOrigin.Unknown &&
                    reference.id.toIntOrNull()?.let { it > 0 } == true) item {
                    TextButton(onClick = { onOpenReference(reference) }) { Text(stringResource(R.string.dynamic_source)) }
                }
            }
        }
        item { HorizontalDivider(); Text(stringResource(R.string.dynamic_comments), style = MaterialTheme.typography.titleMedium) }
        if (state.commentsStatus == DynamicLoadStatus.Loading) item { CircularProgressIndicator() }
        if (state.commentsStatus == DynamicLoadStatus.Loaded && state.comments.isEmpty()) {
            item { Text(stringResource(R.string.dynamic_no_comments)) }
        }
        items(state.comments, key = DynamicComment::id) { comment ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Comment(comment)
                state.replies[comment.id].orEmpty().forEach { reply ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(start = 20.dp)) {
                        Column(Modifier.padding(12.dp)) { Comment(reply) }
                    }
                }
                when {
                    comment.id in state.loadingReplies -> CircularProgressIndicator(Modifier.size(24.dp))
                    comment.id in state.failedReplies -> Retry({ model.loadReplies(comment.id) })
                    comment.childCount > 0 && (comment.id !in state.replies || comment.id in state.repliesHaveMore) ->
                        TextButton(onClick = { model.loadReplies(comment.id) }) {
                            Text(stringResource(R.string.dynamic_replies, comment.childCount))
                        }
                }
                HorizontalDivider()
            }
        }
        item {
            if (state.loadingMoreComments) CircularProgressIndicator()
            else if (state.commentsStatus == DynamicLoadStatus.Failed) Retry(model::loadMoreComments)
            else if (state.commentsHaveMore) TextButton(onClick = model::loadMoreComments) { Text(stringResource(R.string.dynamic_more)) }
        }
    }
}

@Composable
private fun Entry(entry: DynamicEntry, modifier: Modifier, preview: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DynamicAuthorIdentity(entry.author)
        Text(listOf(entry.author.areaName, entry.author.groupName).filter(String::isNotBlank).joinToString(" / "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(remember(entry.contentHtml) { AnnotatedString.fromHtml(entry.contentHtml) },
            maxLines = if (preview) 5 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        Images(entry.imageUrls)
        entry.reference?.let { source ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(source.origin.label()), style = MaterialTheme.typography.labelSmall)
                    if (source.title.isNotBlank()) Text(source.title, style = MaterialTheme.typography.titleSmall)
                    if (!preview && source.description.isNotBlank()) Text(
                        remember(source.description) { AnnotatedString.fromHtml(source.description) })
                    Images(source.imageUrls.take(if (preview) 1 else 9))
                }
            }
        }
        Text(stringResource(R.string.dynamic_counts, entry.commentCount, entry.likeCount), style = MaterialTheme.typography.labelSmall)
        entry.createdAt?.let { Text(remember(it) { DateFormat.format(it) }, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun Comment(comment: DynamicComment) {
    DynamicAuthorIdentity(comment.author)
    comment.replyToName?.takeIf(String::isNotBlank)?.let { Text(stringResource(R.string.dynamic_reply_to, it)) }
    Text(remember(comment.contentHtml) { AnnotatedString.fromHtml(comment.contentHtml) })
    Images(comment.imageUrls)
}

@Composable
private fun Images(urls: List<String>) {
    if (urls.isEmpty()) return
    var selected by remember(urls) { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(urls) { url -> AsyncImage(model = url, contentDescription = stringResource(R.string.dynamic_image),
            modifier = Modifier.size(144.dp).clickable { selected = url }, contentScale = ContentScale.Crop) }
    }
    selected?.let { url -> AlertDialog(onDismissRequest = { selected = null },
        confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.dynamic_close)) } },
        text = { AsyncImage(model = url, contentDescription = stringResource(R.string.dynamic_image),
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 600.dp), contentScale = ContentScale.Fit) }) }
}

@Composable
private fun Retry(onClick: () -> Unit, retained: Boolean = false) {
    Column(Modifier.padding(12.dp)) {
        Text(stringResource(if (retained) R.string.dynamic_refresh_failed else R.string.dynamic_failed))
        TextButton(onClick = onClick) { Text(stringResource(R.string.dynamic_retry)) }
    }
}

@Composable
private fun Message(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message) }
}

private fun DynamicOrigin.label(): Int = when (this) {
    DynamicOrigin.Original, DynamicOrigin.Dynamic -> R.string.dynamic_title
    DynamicOrigin.Post -> R.string.dynamic_origin_post
    DynamicOrigin.Guide -> R.string.dynamic_origin_guide
    DynamicOrigin.Glamour -> R.string.dynamic_origin_glamour
    DynamicOrigin.Unknown -> R.string.dynamic_source
    else -> R.string.dynamic_origin_recruitment
}

private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
