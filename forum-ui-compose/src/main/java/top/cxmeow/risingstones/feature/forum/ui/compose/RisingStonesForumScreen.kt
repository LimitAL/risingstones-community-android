package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumAuthor
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumComment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentOrder
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumLinkParser
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostBodyBlock
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostDetail
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostSummary
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPostVote
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumRichTextSegment
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumResourceUrls
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailUiState
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModelFactory
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumListUiState
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumListViewModel
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumListViewModelFactory
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumLoadStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesForumScreen(
    service: OfficialForumService,
    onOpenAccount: () -> Unit,
    onOpenRecruitment: (() -> Unit)? = null,
    onOpenGlamour: (() -> Unit)? = null,
    onOpenPersonalData: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listViewModel: OfficialForumListViewModel = viewModel(
        key = "rising-stones-forum-list",
        factory = remember(service) { OfficialForumListViewModelFactory(service) },
    )
    val state by listViewModel.state.collectAsStateWithLifecycle()
    var isServicesMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(listViewModel) {
        listViewModel.ensureLoaded()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forum_title)) },
                actions = {
                    if (
                        onOpenGlamour != null ||
                        onOpenRecruitment != null ||
                        onOpenPersonalData != null
                    ) {
                        Box {
                            TextButton(onClick = { isServicesMenuExpanded = true }) {
                                Text(stringResource(R.string.forum_services))
                            }
                            DropdownMenu(
                                expanded = isServicesMenuExpanded,
                                onDismissRequest = { isServicesMenuExpanded = false },
                            ) {
                                onOpenRecruitment?.let { openRecruitment ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.forum_recruitment)) },
                                        onClick = {
                                            isServicesMenuExpanded = false
                                            openRecruitment()
                                        },
                                    )
                                }
                                onOpenGlamour?.let { openGlamour ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.forum_glamour)) },
                                        onClick = {
                                            isServicesMenuExpanded = false
                                            openGlamour()
                                        },
                                    )
                                }
                                onOpenPersonalData?.let { openPersonalData ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.forum_personal_data))
                                        },
                                        onClick = {
                                            isServicesMenuExpanded = false
                                            openPersonalData()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = onOpenAccount) {
                        Text(stringResource(R.string.forum_account))
                    }
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val layoutMode = risingStonesForumLayoutMode(maxWidth.value.toInt())
            val selectedPostId = state.selectedPostId
            when (layoutMode) {
                RisingStonesForumLayoutMode.Compact -> {
                    BackHandler(enabled = selectedPostId != null) {
                        listViewModel.clearSelection()
                    }
                    if (selectedPostId == null) {
                        ForumListPane(
                            state = state,
                            viewModel = listViewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ForumDetailPane(
                            service = service,
                            postId = selectedPostId,
                            showBack = true,
                            onBack = listViewModel::clearSelection,
                            onOpenPost = listViewModel::selectPostId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                RisingStonesForumLayoutMode.Medium,
                RisingStonesForumLayoutMode.Expanded,
                -> {
                    Row(Modifier.fillMaxSize()) {
                        ForumListPane(
                            state = state,
                            viewModel = listViewModel,
                            modifier = Modifier
                                .width(
                                    if (layoutMode == RisingStonesForumLayoutMode.Expanded) {
                                        420.dp
                                    } else {
                                        340.dp
                                    },
                                )
                                .fillMaxHeight(),
                        )
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (selectedPostId == null) {
                                ForumMessage(
                                    text = stringResource(R.string.forum_select_post),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                ForumDetailPane(
                                    service = service,
                                    postId = selectedPostId,
                                    showBack = false,
                                    onBack = listViewModel::clearSelection,
                                    onOpenPost = listViewModel::selectPostId,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(max = 920.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumListPane(
    state: OfficialForumListUiState,
    viewModel: OfficialForumListViewModel,
    modifier: Modifier,
) {
    Column(modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.contentKind == OfficialForumContentKind.Post,
                    onClick = {
                        viewModel.setContentKind(OfficialForumContentKind.Post)
                    },
                    label = { Text(stringResource(R.string.forum_posts)) },
                )
                FilterChip(
                    selected = state.contentKind == OfficialForumContentKind.Guide,
                    onClick = {
                        viewModel.setContentKind(OfficialForumContentKind.Guide)
                    },
                    label = { Text(stringResource(R.string.forum_guides)) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::refresh) {
                    Text(stringResource(R.string.forum_refresh))
                }
            }
            OutlinedTextField(
                value = state.searchText,
                onValueChange = viewModel::setSearchText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.forum_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                trailingIcon = {
                    TextButton(onClick = viewModel::submitSearch) {
                        Text(stringResource(R.string.forum_search))
                    }
                },
            )
            if (
                state.contentKind == OfficialForumContentKind.Post &&
                state.parts.isNotEmpty()
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.parts, key = { it.id }) { part ->
                        FilterChip(
                            selected = part.id in state.selectedPartIds,
                            onClick = { viewModel.togglePart(part.id) },
                            label = { Text(part.name, maxLines = 1) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        when {
            state.status == OfficialForumLoadStatus.Loading && state.posts.isEmpty() -> {
                ForumLoading(Modifier.fillMaxSize())
            }

            state.status == OfficialForumLoadStatus.Failed && state.posts.isEmpty() -> {
                ForumRetry(
                    text = stringResource(R.string.forum_load_failed),
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.status == OfficialForumLoadStatus.Loaded && state.posts.isEmpty() -> {
                ForumMessage(
                    text = stringResource(R.string.forum_empty),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.posts, key = OfficialForumPostSummary::id) { post ->
                        ForumPostCard(
                            post = post,
                            selected = state.selectedPostId == post.id,
                            onClick = { viewModel.select(post) },
                        )
                    }
                    item {
                        when {
                            state.isLoadingMore -> ForumLoading(
                                Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                            )

                            state.loadMoreFailed -> ForumRetry(
                                text = stringResource(R.string.forum_load_more_failed),
                                onRetry = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            state.posts.size < state.total -> Button(
                                onClick = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.forum_load_more))
                            }
                        }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.forum_anonymous_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumPostCard(
    post: OfficialForumPostSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ForumAvatar(post.author, 34.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = post.author.characterName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            forumTime(post.lastCommentAt ?: post.createdAt),
                            post.author.locationText.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ForumBadge(post.part.name)
            }
            Text(
                text = post.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.excerpt.isNotBlank()) {
                Text(
                    text = post.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            post.coverImageUrls.firstOrNull()?.let { imageUrl ->
                RisingStonesRemoteImage(
                    url = imageUrl,
                    contentDescription = post.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                    fallback = {},
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.forum_comment_count, post.commentCount),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(R.string.forum_like_count, post.likeCount),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(R.string.forum_read_count, post.readCount),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (post.isTop) ForumBadge(stringResource(R.string.forum_pinned))
                if (post.isRefined) ForumBadge(stringResource(R.string.forum_refined))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForumDetailPane(
    service: OfficialForumService,
    postId: Int,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenPost: (Int) -> Unit,
    modifier: Modifier,
) {
    val detailViewModel: OfficialForumDetailViewModel = viewModel(
        key = "rising-stones-forum-detail-$postId",
        factory = remember(service, postId) {
            OfficialForumDetailViewModelFactory(service, postId)
        },
    )
    val state by detailViewModel.state.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<OfficialForumComment?>(null) }

    Box(modifier) {
        when {
            state.status == OfficialForumLoadStatus.Loading && state.detail == null -> {
                ForumLoading(Modifier.fillMaxSize())
            }

            state.status == OfficialForumLoadStatus.Failed && state.detail == null -> {
                ForumRetry(
                    text = stringResource(R.string.forum_load_failed),
                    onRetry = detailViewModel::load,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.detail != null -> {
                ForumDetailContent(
                    state = state,
                    viewModel = detailViewModel,
                    showBack = showBack,
                    onBack = onBack,
                    onOpenPost = onOpenPost,
                    onDeleteRequested = { pendingDeletion = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    state.selectedSubCommentRootId?.let { rootId ->
        val root = state.comments.firstOrNull { it.id == rootId }
        ModalBottomSheet(onDismissRequest = detailViewModel::dismissSubComments) {
            ForumRepliesSheet(
                root = root,
                replies = state.subCommentsByRootId[rootId].orEmpty(),
                isLoading = rootId in state.loadingSubCommentIds,
                onClose = detailViewModel::dismissSubComments,
                onDeleteRequested = { pendingDeletion = it },
            )
        }
    }

    pendingDeletion?.let { comment ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(stringResource(R.string.forum_delete_confirm_title)) },
            text = { Text(stringResource(R.string.forum_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    detailViewModel.deleteComment(comment)
                    pendingDeletion = null
                }) {
                    Text(stringResource(R.string.forum_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.forum_cancel))
                }
            },
        )
    }
}

@Composable
private fun ForumDetailContent(
    state: OfficialForumDetailUiState,
    viewModel: OfficialForumDetailViewModel,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenPost: (Int) -> Unit,
    onDeleteRequested: (OfficialForumComment) -> Unit,
    modifier: Modifier,
) {
    val detail = state.detail ?: return
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showBack) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.forum_back))
                }
            }
        }
        item { ForumDetailHeader(detail) }
        item { ForumPostBody(detail, onOpenPost) }
        if (detail.votes.isNotEmpty()) {
            items(detail.votes, key = OfficialForumPostVote::id) { vote ->
                ForumVoteCard(vote)
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.forum_comments),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            ForumCommentControls(state, viewModel)
        }
        when {
            state.commentsStatus == OfficialForumLoadStatus.Loading && state.comments.isEmpty() -> {
                item { ForumLoading(Modifier.fillMaxWidth().height(88.dp)) }
            }

            state.commentsStatus == OfficialForumLoadStatus.Failed && state.comments.isEmpty() -> {
                item {
                    ForumRetry(
                        text = stringResource(R.string.forum_load_failed),
                        onRetry = { viewModel.refreshComments(force = true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            state.comments.isEmpty() -> {
                item {
                    ForumMessage(
                        text = stringResource(R.string.forum_empty),
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                    )
                }
            }

            else -> {
                items(state.comments, key = OfficialForumComment::id) { comment ->
                    ForumCommentCard(
                        comment = comment,
                        previews = state.subCommentsByRootId[comment.id].orEmpty(),
                        onOpenReplies = { viewModel.openSubComments(comment) },
                        onDelete = onDeleteRequested,
                    )
                }
            }
        }
        item {
            when {
                state.isLoadingMoreComments -> ForumLoading(
                    Modifier.fillMaxWidth().height(64.dp),
                )

                state.loadMoreFailed -> ForumRetry(
                    text = stringResource(R.string.forum_load_more_failed),
                    onRetry = viewModel::loadMoreComments,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.comments.size < state.commentTotal -> Button(
                    onClick = viewModel::loadMoreComments,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.forum_load_more))
                }
            }
        }
    }
}

@Composable
private fun ForumDetailHeader(detail: OfficialForumPostDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ForumAvatar(detail.author, 42.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = detail.author.characterName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(
                        detail.author.locationText.takeIf(String::isNotBlank),
                        forumTime(detail.createdAt),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ForumBadge(detail.part.name)
        }
        Text(
            text = detail.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.forum_comment_count, detail.commentCount),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(R.string.forum_like_count, detail.likeCount),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(R.string.forum_star_count, detail.starCount),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(R.string.forum_read_count, detail.readCount),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ForumPostBody(
    detail: OfficialForumPostDetail,
    onOpenPost: (Int) -> Unit,
) {
    val blocks = detail.bodyBlocks
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (blocks.isNotEmpty()) {
            blocks.forEach { block -> ForumPostBodyBlock(block, onOpenPost) }
        } else if (detail.bodySegments.isNotEmpty()) {
            ForumRichSegments(detail.bodySegments, onOpenPost)
        } else if (detail.bodyText.isNotBlank()) {
            Text(detail.bodyText, style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                stringResource(R.string.forum_no_content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForumPostBodyBlock(
    block: OfficialForumPostBodyBlock,
    onOpenPost: (Int) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    when (block) {
        is OfficialForumPostBodyBlock.Paragraph -> ForumRichSegments(
            segments = block.segments,
            onOpenPost = onOpenPost,
        )
        is OfficialForumPostBodyBlock.Image -> ForumRemoteContentImage(block.url)
        is OfficialForumPostBodyBlock.VideoEmbed -> Button(
            onClick = { runCatching { uriHandler.openUri(block.url) } },
        ) {
            Text(stringResource(R.string.forum_open_video))
        }

        is OfficialForumPostBodyBlock.Table -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            block.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { cell ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            ForumRichSegments(
                                segments = cell,
                                onOpenPost = onOpenPost,
                                modifier = Modifier.padding(8.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        is OfficialForumPostBodyBlock.Disclosure -> {
            var expanded by remember(block) {
                mutableStateOf(block.initiallyExpanded)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = richText(block.title),
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (expanded) {
                                stringResource(R.string.forum_collapse)
                            } else {
                                stringResource(R.string.forum_expand)
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            block.blocks.forEach { nested ->
                                ForumPostBodyBlock(nested, onOpenPost)
                            }
                        }
                    }
                }
            }
        }

        is OfficialForumPostBodyBlock.Callout -> Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(10.dp),
        ) {
            ForumRichSegments(
                segments = block.segments,
                onOpenPost = onOpenPost,
                modifier = Modifier.padding(12.dp),
                textColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        OfficialForumPostBodyBlock.Divider -> HorizontalDivider()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForumRichSegments(
    segments: List<OfficialForumRichTextSegment>,
    onOpenPost: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = Color.Unspecified,
) {
    val uriHandler = LocalUriHandler.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is OfficialForumRichTextSegment.Text -> Text(
                    text = segment.value,
                    style = textStyle,
                    color = textColor,
                )

                is OfficialForumRichTextSegment.Link -> Text(
                    text = segment.text.ifBlank {
                        stringResource(R.string.forum_open_link)
                    },
                    style = textStyle,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        val postId = OfficialForumLinkParser.postId(segment.url)
                        if (postId != null) {
                            onOpenPost(postId)
                        } else {
                            runCatching { uriHandler.openUri(segment.url) }
                        }
                    },
                )

                is OfficialForumRichTextSegment.Emoji -> RisingStonesRemoteImage(
                    url = OfficialForumResourceUrls.emojiImageUrl(segment.number),
                    contentDescription = "[emo${segment.number}]",
                    modifier = Modifier
                        .width(28.dp)
                        .height(34.dp),
                    contentScale = ContentScale.Fit,
                    fallback = {
                        Text(
                            text = "[emo${segment.number}]",
                            style = textStyle,
                            color = textColor,
                        )
                    },
                )

                is OfficialForumRichTextSegment.Image -> ForumRemoteContentImage(segment.url)
            }
        }
    }
}

@Composable
private fun ForumRemoteContentImage(url: String) {
    val uriHandler = LocalUriHandler.current
    Surface(
        onClick = { runCatching { uriHandler.openUri(url) } },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        RisingStonesRemoteImage(
            url = url,
            contentDescription = stringResource(R.string.forum_open_image),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 520.dp),
            contentScale = ContentScale.Fit,
            fallback = {
                ForumMessage(
                    text = stringResource(R.string.forum_image_failed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                )
            },
        )
    }
}

@Composable
private fun ForumVoteCard(vote: OfficialForumPostVote) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = vote.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            vote.options.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (option.isParticipant) "✓ ${option.title}" else option.title,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = option.totalVoteCount.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForumCommentControls(
    state: OfficialForumDetailUiState,
    viewModel: OfficialForumDetailViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ForumCommentOrderChip(
                selected = state.commentOrder == OfficialForumCommentOrder.Hottest,
                label = stringResource(R.string.forum_hottest),
                onClick = { viewModel.setCommentOrder(OfficialForumCommentOrder.Hottest) },
            )
            ForumCommentOrderChip(
                selected = state.commentOrder == OfficialForumCommentOrder.Latest,
                label = stringResource(R.string.forum_latest),
                onClick = { viewModel.setCommentOrder(OfficialForumCommentOrder.Latest) },
            )
            ForumCommentOrderChip(
                selected = state.commentOrder == OfficialForumCommentOrder.Earliest,
                label = stringResource(R.string.forum_earliest),
                onClick = { viewModel.setCommentOrder(OfficialForumCommentOrder.Earliest) },
            )
        }
        Row(
            modifier = Modifier
                .clickable(onClick = viewModel::toggleOnlyPostAuthor)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.onlyPostAuthor,
                onCheckedChange = { viewModel.toggleOnlyPostAuthor() },
            )
            Text(stringResource(R.string.forum_only_author))
        }
    }
}

@Composable
private fun ForumCommentOrderChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ForumCommentCard(
    comment: OfficialForumComment,
    previews: List<OfficialForumComment>,
    onOpenReplies: () -> Unit,
    onDelete: (OfficialForumComment) -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ForumAvatar(comment.author, 34.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = comment.author.characterName,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOfNotNull(
                            forumTime(comment.createdAt),
                            comment.ipLocation?.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (comment.isPostAuthor) {
                    ForumBadge(stringResource(R.string.forum_only_author))
                }
                if (comment.isMine) {
                    TextButton(onClick = { onDelete(comment) }) {
                        Text(stringResource(R.string.forum_delete))
                    }
                }
            }
            if (comment.replyToAuthorName != null) {
                Text(
                    text = "↪ ${comment.replyToAuthorName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(comment.bodyText, style = MaterialTheme.typography.bodyMedium)
            comment.imageUrls.forEach { url -> ForumRemoteContentImage(url) }
            previews.take(2).forEach { preview ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "${preview.author.characterName}: ${preview.bodyText}",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (comment.childCount > 0) {
                TextButton(onClick = onOpenReplies) {
                    Text(stringResource(R.string.forum_view_replies, comment.childCount))
                }
            }
        }
    }
}

@Composable
private fun ForumRepliesSheet(
    root: OfficialForumComment?,
    replies: List<OfficialForumComment>,
    isLoading: Boolean,
    onClose: () -> Unit,
    onDeleteRequested: (OfficialForumComment) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.forum_replies),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.forum_close))
            }
        }
        root?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${it.author.characterName}: ${it.bodyText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (it.isMine) {
                    TextButton(onClick = { onDeleteRequested(it) }) {
                        Text(stringResource(R.string.forum_delete))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            isLoading && replies.isEmpty() -> ForumLoading(Modifier.fillMaxWidth().height(100.dp))
            replies.isEmpty() -> ForumMessage(
                text = stringResource(R.string.forum_empty),
                modifier = Modifier.fillMaxWidth().height(100.dp),
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(replies, key = OfficialForumComment::id) { reply ->
                    ForumCommentCard(
                        comment = reply,
                        previews = emptyList(),
                        onOpenReplies = {},
                        onDelete = onDeleteRequested,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForumAvatar(author: OfficialForumAuthor, size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        RisingStonesRemoteImage(
            url = author.avatarUrl,
            contentDescription = author.characterName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            fallback = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        author.characterName.firstOrNull()?.toString() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        )
    }
}

@Composable
private fun ForumBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun ForumLoading(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ForumMessage(text: String, modifier: Modifier) {
    Box(modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ForumRetry(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.forum_retry))
        }
    }
}

private fun richText(segments: List<OfficialForumRichTextSegment>): String =
    segments.joinToString(separator = "") { segment ->
        when (segment) {
            is OfficialForumRichTextSegment.Text -> segment.value
            is OfficialForumRichTextSegment.Emoji -> "[emo${segment.number}]"
            is OfficialForumRichTextSegment.Link -> segment.text
            is OfficialForumRichTextSegment.Image -> ""
        }
    }

private val ForumTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

private fun forumTime(value: Instant?): String? = value?.let(ForumTimeFormatter::format)
