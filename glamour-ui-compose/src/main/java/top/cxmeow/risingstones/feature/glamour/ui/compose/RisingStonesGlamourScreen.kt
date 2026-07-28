package top.cxmeow.risingstones.feature.glamour.ui.compose

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
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
    val viewModel: GlamourViewModel = viewModel(
        key = "rising-stones-glamour",
        factory = remember(service) { GlamourViewModelFactory(service) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.glamour_back))
                    }
                },
                title = { Text(stringResource(R.string.glamour_title)) },
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        GlamourDetailPane(
                            state = state,
                            viewModel = viewModel,
                            showBack = true,
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
    modifier: Modifier,
) {
    Column(
        modifier = modifier.testTag("glamour-list-pane"),
    ) {
        GlamourListControls(state = state, viewModel = viewModel)
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
                )
            }
        }
    }
}

@Composable
private fun GlamourListControls(
    state: GlamourUiState,
    viewModel: GlamourViewModel,
) {
    var query by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlamourSourceChip(
                selected = state.source == GlamourListSource.Community,
                label = stringResource(R.string.glamour_community),
                onClick = { viewModel.selectSource(GlamourListSource.Community) },
            )
            GlamourSourceChip(
                selected = state.source == GlamourListSource.Favorites,
                label = stringResource(R.string.glamour_favorites),
                onClick = { viewModel.selectSource(GlamourListSource.Favorites) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlamourOrderChip(
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
        }

        Row(
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
) {
    LazyColumn(
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
                Text(
                    text = item.author.displayLine(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    modifier: Modifier,
) {
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
                    onFavorite = { viewModel.toggleFavorite(detail.id) },
                    onClearNotice = viewModel::clearNotice,
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
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
                Text(
                    text = detail.author.displayLine(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            text = message ?: stringResource(R.string.glamour_unavailable),
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
            Text(message, modifier = Modifier.weight(1f))
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

private fun top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor.displayLine(): String =
    listOf(characterName, areaName, groupName).filter(String::isNotBlank).joinToString(" · ")

private val GlamourDyeLabel: (top.cxmeow.risingstones.feature.glamour.domain.GlamourDye) -> String =
    { it.name }

private val DetailDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
