package top.cxmeow.risingstones.feature.message.ui.compose

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.message.presentation.*

enum class MessageLayoutMode { Compact, Medium, Expanded }
fun messageLayoutMode(widthDp: Int): MessageLayoutMode = when {
    widthDp < 600 -> MessageLayoutMode.Compact
    widthDp < 840 -> MessageLayoutMode.Medium
    else -> MessageLayoutMode.Expanded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesMessageScreen(
    viewModel: MessageViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTarget: ((MessageTarget) -> Unit)? = null,
    canOpenTarget: (MessageTarget) -> Boolean = { true },
    onOpenOfficialLink: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.ensureLoaded() }
    val categoryScroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val listScroll = rememberSaveable(state.query?.category, state.query?.commentChannel,
        state.query?.recruitmentChannel, saver = LazyListState.Saver) { LazyListState() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val mode = messageLayoutMode(maxWidth.value.toInt())
        val back = {
            if (mode == MessageLayoutMode.Compact && state.query != null) viewModel.clearCategory()
            else onNavigateBack()
        }
        BackHandler(onBack = back)
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.message_title)) },
                navigationIcon = { TextButton(onClick = back) { Text(stringResource(R.string.message_back)) } })
        }) { padding ->
            val protectedFailure = state.status in listOf(MessageLoadStatus.AuthenticationRequired, MessageLoadStatus.Unavailable)
            if (protectedFailure) {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.message_sign_in))
                }
            } else if (mode == MessageLayoutMode.Compact) {
                if (state.query == null) Categories(state, viewModel, categoryScroll, Modifier.fillMaxSize().padding(padding))
                else MessageList(state, viewModel, listScroll, onOpenTarget, canOpenTarget, onOpenOfficialLink,
                    Modifier.fillMaxSize().padding(padding))
            } else Row(Modifier.fillMaxSize().padding(padding)) {
                Categories(state, viewModel, categoryScroll, Modifier.width(if (mode == MessageLayoutMode.Medium) 220.dp else 280.dp).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    if (state.query == null) Text(stringResource(R.string.message_select), Modifier.padding(24.dp))
                    else MessageList(state, viewModel, listScroll, onOpenTarget, canOpenTarget, onOpenOfficialLink,
                        Modifier.widthIn(max = 840.dp).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Categories(state: MessageUiState, model: MessageViewModel, scroll: LazyListState, modifier: Modifier) {
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.message_read_notice), style = MaterialTheme.typography.bodySmall) }
        if (state.summaryStatus == MessageLoadStatus.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.summaryStatus == MessageLoadStatus.Failed) item { Failure(model::refreshSummary) }
        items(MessageCategory.entries) { category ->
            val count = state.unread?.count(category)
            Card(onClick = { model.selectCategory(category) }, colors = CardDefaults.cardColors(
                containerColor = if (state.query?.category == category) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(category.label()))
                    if (count != null && count > 0) Text(stringResource(R.string.message_unread, count))
                }
            }
        }
        item { TextButton(onClick = model::refreshSummary) { Text(stringResource(R.string.message_refresh_counts)) } }
    }
}

@Composable
private fun MessageList(state: MessageUiState, model: MessageViewModel, scroll: LazyListState,
    onOpenTarget: ((MessageTarget) -> Unit)?, canOpenTarget: (MessageTarget) -> Boolean,
    onOpenOfficialLink: ((String) -> Unit)?, modifier: Modifier) {
    val query = state.query ?: return
    val authors by model.authorState.collectAsStateWithLifecycle()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(query.category.label()), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = model::refreshMessages) { Text(stringResource(R.string.message_refresh)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.category == MessageCategory.Comments) items(MessageCommentChannel.entries) { channel ->
                FilterChip(selected = query.commentChannel == channel, onClick = { model.selectCommentChannel(channel) },
                    label = { Text(stringResource(if (channel == MessageCommentChannel.Received) R.string.message_received else R.string.message_sent)) })
            }
            if (query.category in listOf(MessageCategory.Recruitment, MessageCategory.Responses)) {
                items(MessageRecruitmentChannel.entries) { channel ->
                    FilterChip(selected = query.recruitmentChannel == channel, onClick = { model.selectRecruitmentChannel(channel) },
                        label = { Text(stringResource(channel.label())) })
                }
            }
        }
        if (state.status == MessageLoadStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize().testTag("message-content-list"), state = scroll, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.status == MessageLoadStatus.Failed) item {
                Failure(model::refreshMessages, retained = state.items.isNotEmpty(), unavailable = state.failureCode == 10401)
            }
            if (state.status == MessageLoadStatus.Loaded && state.items.isEmpty()) item { Text(stringResource(R.string.message_empty)) }
            items(state.items, key = CommunityMessage::key) { message ->
                MessageCard(message, authors.authorsByKey[message.key], state.selectedKey == message.key, { model.selectMessage(message.key) },
                    onOpenTarget, canOpenTarget, onOpenOfficialLink)
            }
            item {
                if (state.loadingMore) CircularProgressIndicator()
                else if (state.loadMoreFailed) Failure(model::loadMore)
                else if (state.hasMore) TextButton(onClick = model::loadMore) { Text(stringResource(R.string.message_more)) }
            }
        }
    }
}

@Composable
private fun MessageCard(message: CommunityMessage, author: MessageAuthorTarget?, expanded: Boolean, onSelect: () -> Unit,
    onOpenTarget: ((MessageTarget) -> Unit)?, canOpenTarget: (MessageTarget) -> Boolean,
    onOpenOfficialLink: ((String) -> Unit)?) {
    Card(onClick = onSelect) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message.title.ifBlank { stringResource(message.category.label()) }, style = MaterialTheme.typography.titleSmall)
            MessageAuthorName(message.authorName, author)
            if (message.authorLocation.isNotBlank()) Text(message.authorLocation, style = MaterialTheme.typography.labelSmall)
            if (message.contentHtml.isNotBlank()) MessageText(message.contentHtml, expanded)
            if (message.contextHtml.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.padding(12.dp)) { MessageText(message.contextHtml, expanded) }
            }
            if (expanded) {
                message.contactInformation?.let {
                    Text(stringResource(R.string.message_contact), style = MaterialTheme.typography.labelMedium)
                    MessageText(it, true)
                }
                message.imageUrls.forEach { url ->
                    AsyncImage(model = url, contentDescription = stringResource(R.string.message_image),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 400.dp), contentScale = ContentScale.Fit)
                }
            }
            message.createdAt?.let { Text(remember(it) { DateFormat.format(it) }, style = MaterialTheme.typography.labelSmall) }
            message.target?.takeIf(canOpenTarget)?.let { target ->
                if (onOpenTarget != null) TextButton(onClick = { onOpenTarget(target) }) { Text(stringResource(R.string.message_source)) }
            }
            message.officialLink?.let { url ->
                if (onOpenOfficialLink != null) TextButton(onClick = { onOpenOfficialLink(url) }) { Text(stringResource(R.string.message_official_link)) }
            }
        }
    }
}

@Composable
private fun MessageText(html: String, expanded: Boolean) {
    Text(remember(html) { Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim() },
        maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Failure(retry: () -> Unit, retained: Boolean = false, unavailable: Boolean = false) {
    Column(Modifier.padding(12.dp)) {
        Text(stringResource(when {
            unavailable -> R.string.message_category_unavailable
            retained -> R.string.message_refresh_failed
            else -> R.string.message_failed
        }))
        TextButton(onClick = retry) { Text(stringResource(R.string.message_retry)) }
    }
}

private fun MessageCategory.label(): Int = when (this) {
    MessageCategory.System -> R.string.message_system
    MessageCategory.Mentions -> R.string.message_mentions
    MessageCategory.Comments -> R.string.message_comments
    MessageCategory.Likes -> R.string.message_likes
    MessageCategory.Recruitment -> R.string.message_recruitment
    MessageCategory.Responses -> R.string.message_responses
}
private fun MessageRecruitmentChannel.label(): Int = when (this) {
    MessageRecruitmentChannel.Beginner -> R.string.message_beginner
    MessageRecruitmentChannel.Duty -> R.string.message_duty
    MessageRecruitmentChannel.Guild -> R.string.message_guild
    MessageRecruitmentChannel.Other -> R.string.message_other
}
private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
