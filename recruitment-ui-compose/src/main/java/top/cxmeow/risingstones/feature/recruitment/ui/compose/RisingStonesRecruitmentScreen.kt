package top.cxmeow.risingstones.feature.recruitment.ui.compose

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.CommunityRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentDetail
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentService
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentSummary
import top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentActionEligibilityService
import top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentUiState
import top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentViewModel
import top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentViewModelFactory
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentInteractionState
import top.cxmeow.risingstones.feature.recruitment.presentation.RolePlayDirectorySection

/**
 * Replaceable Material 3 reference UI for the public recruitment contracts.
 *
 * Compact uses list -> detail navigation. Medium and expanded widths keep a stable
 * list/detail selection, with 340dp and 420dp list panes respectively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesRecruitmentScreen(
    service: DutyRecruitmentService,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DutyRecruitmentViewModel = viewModel(
        key = "rising-stones-recruitment-${System.identityHashCode(service)}",
        factory = remember(service) { DutyRecruitmentViewModelFactory(service) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interaction by viewModel.interactionState.collectAsStateWithLifecycle()
    val openDirectory = RecruitmentDirectoryNavigation(service, state, "main")
    val listScroll = rememberSaveable(state.board, saver = LazyListState.Saver) { LazyListState() }
    val detailScroll = rememberSaveable(state.board, state.selectedId, saver = LazyListState.Saver) { LazyListState() }
    var filtersOpen by rememberSaveable(state.board) { mutableStateOf(false) }
    RecruitmentCapabilityBoundary(service, viewModel)
    RecruitmentInteractionPanels(state, interaction, viewModel)
    if (filtersOpen) RecruitmentFilterDialog(state, viewModel) { filtersOpen = false }

    BackHandler {
        if (state.selectedId != null) viewModel.clearSelection() else onNavigateBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recruitment_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (state.selectedId != null) viewModel.clearSelection()
                            else onNavigateBack()
                        },
                    ) {
                        Text(stringResource(R.string.recruitment_back))
                    }
                },
                actions = {
                    TextButton(onClick = { filtersOpen = true }, modifier = Modifier.testTag("recruitment-filters")) {
                        Text(stringResource(R.string.recruitment_filters))
                    }
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.recruitment_refresh))
                    }
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val layoutMode = risingStonesRecruitmentLayoutMode(maxWidth.value.toInt())
            when (layoutMode) {
                RisingStonesRecruitmentLayoutMode.Compact -> {
                    if (state.selectedId == null) {
                        RecruitmentListPane(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            scroll = listScroll,
                        )
                    } else {
                        RecruitmentDetailPane(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onOpenDirectory = openDirectory,
                            scroll = detailScroll,
                        )
                    }
                }

                RisingStonesRecruitmentLayoutMode.Medium,
                RisingStonesRecruitmentLayoutMode.Expanded,
                -> Row(Modifier.fillMaxSize()) {
                    RecruitmentListPane(
                        state = state,
                        viewModel = viewModel,
                        scroll = listScroll,
                        modifier = Modifier
                            .width(
                                if (layoutMode == RisingStonesRecruitmentLayoutMode.Expanded) {
                                    420.dp
                                } else {
                                    340.dp
                                },
                            )
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    RecruitmentDetailPane(
                        state = state,
                        viewModel = viewModel,
                        onOpenDirectory = openDirectory,
                        scroll = detailScroll,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecruitmentListPane(
    state: DutyRecruitmentUiState,
    viewModel: DutyRecruitmentViewModel,
    modifier: Modifier,
    scroll: LazyListState,
) {
    val authors by viewModel.authorState.collectAsStateWithLifecycle()
    val boards = buildList {
        add(RecruitmentBoardKind.Duty)
        add(RecruitmentBoardKind.Beginner)
        if (viewModel.hasCommunityIdentity) add(RecruitmentBoardKind.Guild)
        add(RecruitmentBoardKind.Other)
        add(RecruitmentBoardKind.RolePlay)
    }
    val listError = state.listError
    val empty = if (state.board == RecruitmentBoardKind.Duty) {
        state.dutyItems.isEmpty()
    } else {
        state.communityItems.isEmpty()
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            boards.forEach { board ->
                FilterChip(
                    selected = state.board == board,
                    onClick = { viewModel.selectBoard(board) },
                    label = { Text(board.label()) },
                )
            }
        }
        HorizontalDivider()
        when {
            state.isLoading && empty -> RecruitmentCenteredMessage {
                CircularProgressIndicator()
            }

            listError != null && empty -> RecruitmentCenteredMessage {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.recruitment_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.recruitment_retry))
                    }
                }
            }

            empty -> RecruitmentCenteredMessage {
                Text(stringResource(R.string.recruitment_empty))
            }

            else -> LazyColumn(
                state = scroll,
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (listError != null) {
                    item {
                        Text(
                            text = stringResource(R.string.recruitment_load_failed),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.board == RecruitmentBoardKind.Duty) {
                    items(state.dutyItems, key = DutyRecruitmentSummary::id) { item ->
                        DutyRecruitmentListCard(
                            item = item,
                            selected = state.selectedId == item.id,
                            onClick = { viewModel.selectDetail(item.id) },
                        )
                    }
                } else {
                    items(state.communityItems, key = CommunityRecruitmentSummary::id) { item ->
                        CommunityRecruitmentListCard(
                            authorUuid = authors.communityAuthors[item.id],
                            item = item,
                            selected = state.selectedId == item.id,
                            onClick = { viewModel.selectDetail(item.id) },
                        )
                    }
                }
                if (state.hasMore || state.isLoadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = viewModel::loadMore,
                                enabled = !state.isLoadingMore,
                            ) {
                                Text(
                                    if (state.isLoadingMore) {
                                        stringResource(R.string.recruitment_loading)
                                    } else {
                                        stringResource(R.string.recruitment_more)
                                    },
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
private fun DutyRecruitmentListCard(
    item: DutyRecruitmentSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.dutyName, fontWeight = FontWeight.SemiBold)
            RecruitmentAuthorName(item.characterName, item.uuid)
            Text("${item.areaName}/${item.groupName}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(item.schedule, item.progress, item.strategy)
                .filter(String::isNotBlank)
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
                ?.let {
                    Text(
                        it,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
    }
}

@Composable
private fun CommunityRecruitmentListCard(
    authorUuid: String?,
    item: CommunityRecruitmentSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.title, fontWeight = FontWeight.SemiBold)
            RecruitmentAuthorName(item.authorName, authorUuid)
            listOfNotNull(item.sourceLocation, item.targetLocation)
                .filter(String::isNotBlank)
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            item.summary?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RecruitmentDetailPane(
    state: DutyRecruitmentUiState,
    viewModel: DutyRecruitmentViewModel,
    modifier: Modifier,
    scroll: LazyListState,
    onOpenDirectory: ((RolePlayDirectorySection) -> Unit)? = null,
) {
    val dutyDetail = state.dutyDetail
    val communityDetail = state.communityDetail
    val detailError = state.detailError
    val interaction by viewModel.interactionState.collectAsStateWithLifecycle()
    when {
        state.selectedId == null -> RecruitmentCenteredMessage(modifier) {
            Text(stringResource(R.string.recruitment_select))
        }

        state.isLoadingDetail && dutyDetail == null && communityDetail == null ->
            RecruitmentCenteredMessage(modifier) { CircularProgressIndicator() }

        detailError != null && dutyDetail == null && communityDetail == null ->
            RecruitmentCenteredMessage(modifier) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.recruitment_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retryDetail) {
                        Text(stringResource(R.string.recruitment_retry))
                    }
                }
            }

        dutyDetail != null -> DutyRecruitmentDetailContent(state, interaction, detailError, viewModel, modifier, scroll)
        communityDetail != null ->
            CommunityRecruitmentDetailContent(communityDetail, state, interaction, detailError, viewModel, modifier, onOpenDirectory, scroll)
        else -> RecruitmentCenteredMessage(modifier) {
            RecruitmentReadRetry(viewModel::retryDetail)
        }
    }
}

@Composable
private fun DutyRecruitmentDetailContent(
    state: DutyRecruitmentUiState,
    interaction: RecruitmentInteractionState,
    refreshError: String?,
    viewModel: DutyRecruitmentViewModel,
    modifier: Modifier,
    scroll: LazyListState,
) {
    val detail = state.dutyDetail ?: return
    LazyColumn(
        modifier = modifier.testTag("recruitment-detail-content"),
        state = scroll,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 920.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(detail.summary.dutyName, style = MaterialTheme.typography.headlineSmall)
                RecruitmentAuthorName(detail.summary.characterName, detail.summary.uuid)
                RecruitmentRelayAction(
                    detail.summary.id,
                    RecruitmentBoardKind.Duty,
                    detail.summary.dutyName,
                )
                Text("${detail.summary.areaName}/${detail.summary.groupName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RecruitmentChips(
                    listOf(
                        detail.summary.dutyType,
                        detail.summary.schedule,
                        detail.summary.progress,
                        detail.summary.targetAreaName,
                    ),
                )
                refreshError?.let {
                    RecruitmentReadRetry(viewModel::refreshDetail)
                }
                RecruitmentResponseActions(state, interaction, viewModel)
                interaction.responseError?.let { RecruitmentInteractionErrorText(it) }
                RecruitmentTextSection(stringResource(R.string.recruitment_team), detail.teamDetail)
                RecruitmentTextSection(
                    stringResource(R.string.recruitment_requirements),
                    detail.recruitRequirements,
                )
                RecruitmentTextSection(
                    stringResource(R.string.recruitment_strategy),
                    detail.strategyDescription.ifBlank { detail.summary.strategy },
                )
                TextButton(onClick = viewModel::refreshDetail) {
                    Text(stringResource(R.string.recruitment_refresh_detail))
                }
            }
        }
    }
}

@Composable
private fun CommunityRecruitmentDetailContent(
    detail: CommunityRecruitmentDetail,
    state: DutyRecruitmentUiState,
    interaction: RecruitmentInteractionState,
    refreshError: String?,
    viewModel: DutyRecruitmentViewModel,
    modifier: Modifier,
    onOpenDirectory: ((RolePlayDirectorySection) -> Unit)?,
    scroll: LazyListState,
) {
    val authors by viewModel.authorState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.testTag("recruitment-detail-content"),
        state = scroll,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 920.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(detail.summary.title, style = MaterialTheme.typography.headlineSmall)
                RecruitmentAuthorName(detail.summary.authorName, authors.selectedAuthorUuid)
                RecruitmentRelayAction(detail.summary.id, state.board, detail.summary.title)
                listOfNotNull(
                    detail.summary.sourceLocation,
                    detail.summary.targetLocation,
                ).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RecruitmentChips(detail.information.map { it.value })
                if (state.board == RecruitmentBoardKind.RolePlay && onOpenDirectory != null) {
                    RolePlayDirectoryLinks(onOpenDirectory)
                }
                refreshError?.let {
                    RecruitmentReadRetry(viewModel::refreshDetail)
                }
                RecruitmentResponseActions(state, interaction, viewModel)
                interaction.responseError?.let { RecruitmentInteractionErrorText(it) }
                detail.content.forEach { content ->
                    RecruitmentTextSection(
                        stringResource(R.string.recruitment_description),
                        content.text,
                    )
                    content.imageUrls.forEach { RecruitmentContentImage(it) }
                }
                detail.summary.summary?.let {
                    RecruitmentTextSection(stringResource(R.string.recruitment_summary), it)
                }
                if (state.board == RecruitmentBoardKind.RolePlay) {
                    RecruitmentRolePlayContent(state, interaction, viewModel)
                }
                TextButton(onClick = viewModel::refreshDetail) {
                    Text(stringResource(R.string.recruitment_refresh_detail))
                }
            }
        }
    }
}

@Composable
private fun RecruitmentChips(values: List<String>) {
    val visible = values.filter(String::isNotBlank).distinct()
    if (visible.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visible.forEach { value ->
            AssistChip(onClick = {}, label = { Text(value) })
        }
    }
}

@Composable
private fun RecruitmentTextSection(title: String, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body)
    }
}

@Composable
private fun RecruitmentCenteredMessage(
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
private fun RecruitmentBoardKind.label(): String = stringResource(
    when (this) {
        RecruitmentBoardKind.Duty -> R.string.recruitment_board_duty
        RecruitmentBoardKind.Beginner -> R.string.recruitment_board_beginner
        RecruitmentBoardKind.Guild -> R.string.recruitment_board_guild
        RecruitmentBoardKind.Other -> R.string.recruitment_board_other
        RecruitmentBoardKind.RolePlay -> R.string.recruitment_board_roleplay
    },
)


/** Standalone detail retained across configuration changes and cleared when the route closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesRecruitmentDetailScreen(
    service: DutyRecruitmentService,
    id: Int,
    board: RecruitmentBoardKind,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(id > 0)
    val owner: RecruitmentDetailRouteStore = viewModel(
        key = "rising-stones-recruitment-detail-${System.identityHashCode(service)}-$board-$id",
        factory = RecruitmentDetailRouteStoreFactory,
    )
    val activity = LocalContext.current.findActivity()
    DisposableEffect(owner, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear() }
    }
    val viewModel: DutyRecruitmentViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = remember(service) { DutyRecruitmentViewModelFactory(service, autoLoadList = false) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interaction by viewModel.interactionState.collectAsStateWithLifecycle()
    val openDirectory = RecruitmentDirectoryNavigation(service, state, "source-" + board + "-" + id)
    val detailScroll = rememberSaveable(board, id, saver = LazyListState.Saver) { LazyListState() }
    RecruitmentCapabilityBoundary(service, viewModel)
    RecruitmentInteractionPanels(state, interaction, viewModel)
    val canRead = board != RecruitmentBoardKind.Guild || service.hasCommunityIdentity
    LaunchedEffect(viewModel, id, board, canRead) {
        if (canRead) {
            viewModel.selectBoard(board, loadList = false)
            viewModel.selectDetail(id)
        }
    }
    BackHandler(onBack = onNavigateBack)
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.recruitment_title)) },
            navigationIcon = {
                TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.recruitment_back)) }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                if (canRead) RecruitmentDetailPane(state, viewModel, Modifier.fillMaxSize(), detailScroll, openDirectory)
                else RecruitmentCenteredMessage(Modifier.fillMaxSize()) {
                    Text(stringResource(R.string.recruitment_identity_required))
                }
            }
        }
    }
}

@Composable
private fun RecruitmentCapabilityBoundary(service: DutyRecruitmentService, model: DutyRecruitmentViewModel) {
    val canReadGuild = service.hasCommunityIdentity
    val canWrite = (service as? top.cxmeow.risingstones.feature.recruitment.domain.RecruitmentInteractionService)
        ?.canPerformAuthenticatedWrites == true
    val canAttemptWrite = (service as? RecruitmentActionEligibilityService)?.canAttemptAuthenticatedWrites == true
    val canInteract = canWrite || canAttemptWrite
    var hadGuild by remember(model) { mutableStateOf(canReadGuild) }
    var hadInteraction by remember(model) { mutableStateOf(canInteract) }
    LaunchedEffect(canReadGuild, canInteract) {
        if (hadGuild && !canReadGuild || hadInteraction && !canInteract) model.clearProtectedContent()
        hadGuild = canReadGuild; hadInteraction = canInteract
    }
}

private class RecruitmentDetailRouteStore : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    override fun onCleared() { viewModelStore.clear() }
}

private object RecruitmentDetailRouteStoreFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RecruitmentDetailRouteStore() as T
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
