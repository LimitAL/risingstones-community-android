package top.cxmeow.risingstones.feature.glamour.ui.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAccessory
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourDetail
import top.cxmeow.risingstones.feature.glamour.domain.GlamourEquipment
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListOrder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListSource
import top.cxmeow.risingstones.feature.glamour.domain.GlamourListingSummary
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourUiState
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourViewModel
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesGlamourScreen(
    service: GlamourService,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlamourBrowserScreen(service, null, onNavigateBack, modifier)
}

/** Native author works and public favorite folders. */
@Composable
fun RisingStonesGlamourAuthorScreen(
    service: GlamourService,
    author: GlamourAuthor,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(!author.id.isNullOrBlank())
    GlamourBrowserScreen(service, author, onNavigateBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlamourBrowserScreen(
    service: GlamourService,
    profileAuthor: GlamourAuthor?,
    onNavigateBack: () -> Unit,
    modifier: Modifier,
) {
    val viewModel: GlamourViewModel = viewModel(
        key = "rising-stones-glamour-${System.identityHashCode(service)}-${profileAuthor?.id.orEmpty()}",
        factory = remember(service, profileAuthor?.id) { GlamourViewModelFactory(service, profileAuthor) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listScrollState = rememberLazyListState()
    val detailScrollState = key(state.selectedId) { rememberLazyListState() }
    val interactions by viewModel.interactionState.collectAsStateWithLifecycle()
    var authorId by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showCandidateSearch by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showFolderManager by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val onAuthor: (GlamourAuthor) -> Unit = { author ->
        author.id?.takeIf { it.isNotBlank() && it != profileAuthor?.id }?.let { authorId = it }
    }
    authorId?.let { id ->
        val author = state.selectedDetail?.author?.takeIf { it.id == id }
            ?: state.items.firstOrNull { it.author.id == id }?.author
            ?: GlamourAuthor(id, "", "", "", null)
        RisingStonesGlamourAuthorScreen(service, author, { authorId = null }, modifier)
        return
    }
    if (showCandidateSearch) GlamourCandidateSearchDialog(service,
        onSelect = { viewModel.search(it); showCandidateSearch = false },
        onDismiss = { showCandidateSearch = false })
    if (showFolderManager && viewModel.canManageFolders) GlamourFolderManagerDialog(state, interactions, viewModel) {
        showFolderManager = false
    }
    if (interactions.favoriteTargetId != null) GlamourFavoritePicker(state, interactions, viewModel)
    val onBack = { if (state.selectedId != null) viewModel.clearSelection() else onNavigateBack() }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.glamour_back))
                    }
                },
                title = { Text(stringResource(if (profileAuthor == null) R.string.glamour_title else R.string.glamour_author_works)) },
                actions = {
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = viewModel.hasCommunityIdentity &&
                            !state.isLoading &&
                            !state.isRefreshing,
                    ) {
                        Text(stringResource(R.string.glamour_refresh))
                    }
                },
            )
        },
    ) { contentPadding ->
        if (!viewModel.hasCommunityIdentity) {
            GlamourMessage(
                text = stringResource(R.string.glamour_identity_required),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
            return@Scaffold
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val layoutMode = risingStonesGlamourLayoutMode(maxWidth.value.toInt())
            when (layoutMode) {
                RisingStonesGlamourLayoutMode.Compact -> {
                    BackHandler(enabled = state.selectedId != null) {
                        viewModel.clearSelection()
                    }
                    if (state.selectedId == null) {
                        GlamourListPane(
                            state = state,
                            viewModel = viewModel,
                            profileAuthor = profileAuthor,
                            onAuthor = onAuthor,
                            scrollState = listScrollState,
                            onOpenCandidateSearch = { showCandidateSearch = true },
                            onOpenFolderManager = { showFolderManager = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        GlamourDetailPane(
                            state = state,
                            viewModel = viewModel,
                            showBack = true,
                            scrollState = detailScrollState,
                            onAuthor = onAuthor,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                RisingStonesGlamourLayoutMode.Medium,
                RisingStonesGlamourLayoutMode.Expanded,
                -> {
                    Row(Modifier.fillMaxSize()) {
                        GlamourListPane(
                            state = state,
                            viewModel = viewModel,
                            profileAuthor = profileAuthor,
                            onAuthor = onAuthor,
                            scrollState = listScrollState,
                            onOpenCandidateSearch = { showCandidateSearch = true },
                            onOpenFolderManager = { showFolderManager = true },
                            modifier = Modifier
                                .width(
                                    if (layoutMode == RisingStonesGlamourLayoutMode.Expanded) {
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
                            if (state.selectedId == null) {
                                GlamourMessage(
                                    text = stringResource(R.string.glamour_select),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("glamour-detail-pane"),
                                )
                            } else {
                                GlamourDetailPane(
                                    state = state,
                                    viewModel = viewModel,
                                    showBack = false,
                                    scrollState = detailScrollState,
                                    onAuthor = onAuthor,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(max = 960.dp),
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
private fun GlamourListPane(
    state: GlamourUiState,
    viewModel: GlamourViewModel,
    profileAuthor: GlamourAuthor?,
    onAuthor: (GlamourAuthor) -> Unit,
    onOpenCandidateSearch: () -> Unit,
    onOpenFolderManager: () -> Unit,
    scrollState: LazyListState,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.testTag("glamour-list-pane"),
    ) {
        GlamourListControls(state, viewModel, profileAuthor, onOpenCandidateSearch, onOpenFolderManager)
        HorizontalDivider()
        if (state.isRefreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> GlamourProgress(Modifier.fillMaxSize())
                state.items.isEmpty() && state.listError != null -> {
                    GlamourRecoverableMessage(
                        message = state.listError,
                        onRetry = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.items.isEmpty() -> GlamourMessage(
                    text = stringResource(R.string.glamour_empty),
                    modifier = Modifier.fillMaxSize(),
                )
                else -> GlamourList(
                    state = state,
                    onSelect = viewModel::selectDetail,
                    onLoadMore = viewModel::loadMore,
                    onAuthor = onAuthor,
                    scrollState = scrollState,
                )
            }
        }
    }
}

@Composable
private fun GlamourListControls(
    state: GlamourUiState,
    viewModel: GlamourViewModel,
    profileAuthor: GlamourAuthor?,
    onOpenCandidateSearch: () -> Unit,
    onOpenFolderManager: () -> Unit,
) {
    val browsing by viewModel.browsingState.collectAsStateWithLifecycle()
    var query by rememberSaveable(state.source, browsing.following, state.search?.keywords) {
        androidx.compose.runtime.mutableStateOf(state.search?.takeUnless {
            it.searchByEquipment || it.searchByGlasses || it.searchByOrnament
        }?.keywords.orEmpty())
    }
    var showFilters by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showFilters) GlamourFilterDialog(
        state, browsing, viewModel::loadBrowsingCatalog,
        onApply = { filter, tribe, tags ->
            viewModel.applyBrowsingFilter(filter, tribe, tags)
            showFilters = false
        },
        onDismiss = { showFilters = false },
    )
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (profileAuthor != null) GlamourAuthorHeader(state, profileAuthor, viewModel)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (profileAuthor == null) GlamourSourceChip(
                selected = state.source == GlamourListSource.Community && !browsing.following,
                label = stringResource(R.string.glamour_community),
                onClick = { viewModel.selectSource(GlamourListSource.Community) },
            )
            if (profileAuthor == null && viewModel.supportsBrowsing) GlamourSourceChip(
                selected = browsing.following,
                label = stringResource(R.string.glamour_following),
                onClick = viewModel::selectFollowing,
            )
            GlamourSourceChip(
                selected = state.source == GlamourListSource.Profile,
                label = stringResource(if (profileAuthor == null) R.string.glamour_my_works else R.string.glamour_works),
                onClick = { viewModel.selectSource(GlamourListSource.Profile) },
            )
            GlamourSourceChip(
                selected = state.source == GlamourListSource.Favorites,
                label = stringResource(R.string.glamour_favorites),
                onClick = { viewModel.selectSource(GlamourListSource.Favorites) },
            )
        }

        if (state.source == GlamourListSource.Profile) {
            if (state.isLoadingProfileStatistics) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.profileStatistics?.let { statistics ->
                Text(stringResource(R.string.glamour_profile_counts, statistics.posts, statistics.likes, statistics.favorites),
                    style = MaterialTheme.typography.labelLarge)
            }
        }

        if (!browsing.following) Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.source == GlamourListSource.Community) GlamourOrderChip(
                order = GlamourListOrder.Default,
                label = stringResource(R.string.glamour_order_default),
                state = state,
                viewModel = viewModel,
            )
            GlamourOrderChip(
                order = GlamourListOrder.Hottest,
                label = stringResource(R.string.glamour_order_hottest),
                state = state,
                viewModel = viewModel,
            )
            GlamourOrderChip(
                order = GlamourListOrder.Latest,
                label = stringResource(R.string.glamour_order_latest),
                state = state,
                viewModel = viewModel,
            )
            if (viewModel.supportsBrowsing && state.source == GlamourListSource.Community) {
                val count = browsing.tagIds.size + listOfNotNull(state.filter.raceId,
                    browsing.tribeId, state.filter.genderId, state.filter.createTime).size
                OutlinedButton(onClick = { showFilters = true }) {
                    Text(if (count == 0) stringResource(R.string.glamour_filters)
                        else stringResource(R.string.glamour_filters_count, count))
                }
            }
        }

        if (!browsing.following) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.glamour_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val keywords = query.trim()
                    if (keywords.isNotEmpty()) {
                        viewModel.search(GlamourSearchSelection(keywords))
                    }
                },
                enabled = query.isNotBlank(),
            ) {
                Text(stringResource(R.string.glamour_search))
            }
        }

        state.search?.let { search ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = search.displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        query = ""
                        viewModel.search(null)
                    },
                ) {
                    Text(stringResource(R.string.glamour_clear))
                }
            }
        }

        if (!browsing.following) TextButton(onClick = onOpenCandidateSearch) {
            Text(stringResource(R.string.glamour_find_by_item))
        }

        if (state.source == GlamourListSource.Favorites) {
            if (state.isLoadingFolders && state.folders.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.folders.forEach { folder ->
                        FilterChip(
                            selected = folder.id == state.selectedFolderId,
                            onClick = { viewModel.selectFolder(folder.id) },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.glamour_folder_format,
                                        folder.name,
                                        folder.itemCount,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            if (viewModel.canManageFolders) TextButton(onClick = onOpenFolderManager) {
                Text(stringResource(R.string.glamour_manage_folders))
            }
        }
    }
}

@Composable
private fun GlamourSourceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun GlamourOrderChip(
    order: GlamourListOrder,
    label: String,
    state: GlamourUiState,
    viewModel: GlamourViewModel,
) {
    FilterChip(
        selected = state.search == null && state.filter.order == order,
        onClick = { viewModel.applyFilter(state.filter.copy(order = order)) },
        label = { Text(label) },
    )
}

@Composable
private fun GlamourList(
    state: GlamourUiState,
    onSelect: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onAuthor: (GlamourAuthor) -> Unit,
    scrollState: LazyListState,
) {
    LazyColumn(
        state = scrollState,
        modifier = Modifier.testTag("glamour-list-content"),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.listError?.let { error ->
            item {
                GlamourInlineError(message = error)
            }
        }
        items(state.items, key = GlamourListingSummary::id) { item ->
            GlamourListCard(
                item = item,
                isSelected = item.id == state.selectedId,
                onClick = { onSelect(item.id) },
                onAuthor = { onAuthor(item.author) },
            )
        }
        if (state.hasNextPage || state.isLoadingMore) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.glamour_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlamourListCard(
    item: GlamourListingSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onAuthor: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("glamour-item-${item.id}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlamourImage(
                url = item.imageUrls.firstOrNull(),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                GlamourAuthorLink(item.author, onAuthor)
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.glamour_counts,
                        item.likes,
                        item.favorites,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GlamourDetailPane(
    state: GlamourUiState,
    viewModel: GlamourViewModel,
    showBack: Boolean,
    onAuthor: (GlamourAuthor) -> Unit,
    modifier: Modifier,
    scrollState: LazyListState = rememberLazyListState(),
) {
    val interactions by viewModel.interactionState.collectAsStateWithLifecycle()
    Box(modifier.testTag("glamour-detail-pane")) {
        when {
            state.isLoadingDetail && state.selectedDetail == null -> {
                GlamourProgress(Modifier.fillMaxSize())
            }
            state.selectedDetail == null && state.detailError != null -> {
                GlamourRecoverableMessage(
                    message = state.detailError,
                    onRetry = viewModel::retryDetail,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.selectedDetail == null -> {
                GlamourMessage(
                    text = stringResource(R.string.glamour_select),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                val detail = requireNotNull(state.selectedDetail)
                GlamourDetailContent(
                    detail = detail,
                    isRefreshing = state.isLoadingDetail,
                    error = state.detailError,
                    mutationError = state.error,
                    isMutating = detail.id in state.mutatingIds,
                    showBack = showBack,
                    onBack = viewModel::clearSelection,
                    onRefresh = viewModel::refreshDetail,
                    onLike = { viewModel.toggleLike(detail.id) },
                    onFavorite = {
                        if (!detail.isFavorite && viewModel.supportsCollectionManagement) viewModel.openFavoritePicker(detail.id)
                        else viewModel.toggleFavorite(detail.id)
                    },
                    onClearNotice = viewModel::clearNotice,
                    onAuthor = { onAuthor(detail.author) },
                    interactions = interactions,
                    onClaimCoupon = viewModel::claimSelectedCoupon,
                    onClearCouponNotice = viewModel::clearCouponNotice,
                    onRetryFolders = viewModel::retryFolderRefresh,
                    scrollState = scrollState,
                )
            }
        }
    }
}

@Composable
private fun GlamourDetailContent(
    detail: GlamourDetail,
    isRefreshing: Boolean,
    error: String?,
    mutationError: String?,
    isMutating: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLike: () -> Unit,
    onFavorite: () -> Unit,
    onClearNotice: () -> Unit,
    onAuthor: () -> Unit,
    interactions: top.cxmeow.risingstones.feature.glamour.presentation.GlamourInteractionUiState,
    onClaimCoupon: () -> Unit,
    onClearCouponNotice: () -> Unit,
    onRetryFolders: () -> Unit,
    scrollState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        state = scrollState,
        modifier = Modifier.testTag("glamour-detail-content"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (interactions.folderRefreshError != null) item {
            FolderRefreshNotice(interactions.folderRefreshError, onRetryFolders)
        }
        if (showBack) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.glamour_back_to_list))
                }
            }
        }
        if (isRefreshing) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        error?.let { message ->
            item {
                GlamourInlineError(
                    message = message,
                    actionLabel = stringResource(R.string.glamour_retry),
                    onAction = onRefresh,
                )
            }
        }
        mutationError?.let { message ->
            item {
                GlamourInlineError(
                    message = message,
                    actionLabel = stringResource(R.string.glamour_dismiss),
                    onAction = onClearNotice,
                )
            }
        }
        if (detail.imageUrls.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(detail.imageUrls) { url ->
                        GlamourImage(
                            url = url,
                            contentDescription = detail.title,
                            modifier = Modifier
                                .widthIn(max = 560.dp)
                                .fillParentMaxWidth()
                                .aspectRatio(4f / 3f),
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                GlamourAuthorLink(detail.author, onAuthor)
                detail.createdAt?.let {
                    Text(
                        text = DetailDateFormatter.format(it.atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.raceNames.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.glamour_races_format,
                            detail.raceNames.joinToString(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (detail.description.isNotBlank()) {
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onLike,
                    enabled = !isMutating,
                ) {
                    Text(
                        stringResource(
                            if (detail.isLiked) R.string.glamour_unlike else R.string.glamour_like,
                            detail.likes,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onFavorite,
                    enabled = !isMutating,
                ) {
                    Text(
                        stringResource(
                            if (detail.isFavorite) {
                                R.string.glamour_unfavorite
                            } else {
                                R.string.glamour_favorite
                            },
                            detail.favorites,
                        ),
                    )
                }
                TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Text(stringResource(R.string.glamour_refresh_detail))
                }
            }
        }
        if (detail.isCouponEligible && (detail.isCouponClaimed || !detail.couponInviteCode.isNullOrBlank())) item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.glamour_coupon), style = MaterialTheme.typography.titleMedium)
                    if (detail.isCouponClaimed) {
                        Text(stringResource(R.string.glamour_coupon_claimed))
                    } else {
                        if (!detail.isFollowingAuthor) Text(stringResource(R.string.glamour_coupon_follow_hint))
                        Button(onClick = onClaimCoupon, enabled = !interactions.isClaimingCoupon && !isMutating) {
                            Text(stringResource(if (detail.isFollowingAuthor) R.string.glamour_claim_coupon else R.string.glamour_follow_claim_coupon))
                        }
                    }
                    if (interactions.isClaimingCoupon) LinearProgressIndicator(Modifier.fillMaxWidth())
                    interactions.couponError?.let { Text(glamourErrorMessage(it), color = MaterialTheme.colorScheme.error) }
                    if (interactions.couponClaimedNotice || interactions.couponError != null) {
                        TextButton(onClick = onClearCouponNotice) { Text(stringResource(R.string.glamour_dismiss)) }
                    }
                }
            }
        }
        if (detail.equipments.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.glamour_equipment),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(detail.equipments) { equipment ->
                GlamourEquipmentRow(equipment)
            }
        }
        detail.faceAccessory?.let { accessory ->
            item {
                GlamourAccessoryRow(
                    label = stringResource(R.string.glamour_face_accessory),
                    accessory = accessory,
                )
            }
        }
        detail.fashionAccessory?.let { accessory ->
            item {
                GlamourAccessoryRow(
                    label = stringResource(R.string.glamour_fashion_accessory),
                    accessory = accessory,
                )
            }
        }
    }
}

@Composable
private fun GlamourEquipmentRow(equipment: GlamourEquipment) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = equipment.slot,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = equipment.name ?: stringResource(R.string.glamour_equipment_unknown),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val dyes = equipment.dyes.map(GlamourDyeLabel)
            if (dyes.isNotEmpty()) {
                Text(
                    text = dyes.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GlamourAccessoryRow(
    label: String,
    accessory: GlamourAccessory,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(accessory.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun GlamourImage(
    url: String?,
    contentDescription: String,
    modifier: Modifier,
) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.glamour_no_image),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun GlamourRecoverableMessage(
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = glamourErrorMessage(message),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.glamour_retry))
        }
    }
}

@Composable
private fun GlamourInlineError(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(glamourErrorMessage(message), modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun GlamourProgress(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GlamourMessage(
    text: String,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun glamourErrorMessage(message: String?): String = stringResource(when (message) {
    top.cxmeow.risingstones.feature.glamour.domain.GlamourException.AuthenticationRequired.message -> R.string.glamour_identity_required
    top.cxmeow.risingstones.feature.glamour.domain.GlamourException.MissingDefaultFavoriteFolder.message -> R.string.glamour_folder_unavailable
    else -> R.string.glamour_unavailable
})

private val GlamourDyeLabel: (top.cxmeow.risingstones.feature.glamour.domain.GlamourDye) -> String =
    { it.name }

private val DetailDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")


/** Standalone detail; state survives configuration changes and is cleared when the route closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesGlamourDetailScreen(
    service: GlamourService,
    id: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(id > 0)
    val owner: GlamourDetailRouteStore = viewModel(
        key = "rising-stones-glamour-detail-${System.identityHashCode(service)}-$id",
        factory = GlamourDetailRouteStoreFactory,
    )
    val activity = LocalContext.current.findActivity()
    DisposableEffect(owner, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear() }
    }
    val viewModel: GlamourViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = remember(service) { GlamourViewModelFactory(service, autoLoadList = false) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detailScrollState = rememberLazyListState()
    val canRead = service.hasCommunityIdentity
    val interactions by viewModel.interactionState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, id, canRead) {
        if (canRead) {
            viewModel.selectDetail(id)
        }
    }
    var authorId by rememberSaveable(id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    authorId?.let { selectedAuthorId ->
        val author = state.selectedDetail?.author?.takeIf { it.id == selectedAuthorId }
            ?: GlamourAuthor(selectedAuthorId, "", "", "", null)
        RisingStonesGlamourAuthorScreen(service, author, { authorId = null }, modifier)
        return
    }
    if (interactions.favoriteTargetId != null) GlamourFavoritePicker(state, interactions, viewModel)
    BackHandler(onBack = onNavigateBack)
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.glamour_title)) },
            navigationIcon = {
                TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.glamour_back)) }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                if (canRead) GlamourDetailPane(state, viewModel, showBack = false,
                    onAuthor = { authorId = it.id?.takeIf(String::isNotBlank) }, modifier = Modifier.fillMaxSize(),
                    scrollState = detailScrollState)
                else GlamourMessage(stringResource(R.string.glamour_identity_required), Modifier.fillMaxSize())
            }
        }
    }
}

private class GlamourDetailRouteStore : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    override fun onCleared() { viewModelStore.clear() }
}

private object GlamourDetailRouteStoreFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GlamourDetailRouteStore() as T
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
