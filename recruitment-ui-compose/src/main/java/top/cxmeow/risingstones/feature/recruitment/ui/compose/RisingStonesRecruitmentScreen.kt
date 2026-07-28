package top.cxmeow.risingstones.feature.recruitment.ui.compose

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentUiState
import top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentViewModel
import top.cxmeow.risingstones.feature.recruitment.presentation.DutyRecruitmentViewModelFactory
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

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
        key = "rising-stones-recruitment",
        factory = remember(service) { DutyRecruitmentViewModelFactory(service) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                        )
                    } else {
                        RecruitmentDetailPane(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                RisingStonesRecruitmentLayoutMode.Medium,
                RisingStonesRecruitmentLayoutMode.Expanded,
                -> Row(Modifier.fillMaxSize()) {
                    RecruitmentListPane(
                        state = state,
                        viewModel = viewModel,
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
) {
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
                    Text(listError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.recruitment_retry))
                    }
                }
            }

            empty -> RecruitmentCenteredMessage {
                Text(stringResource(R.string.recruitment_empty))
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (listError != null) {
                    item {
                        Text(
                            text = listError,
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
            Text(
                "${item.characterName} · ${item.areaName}/${item.groupName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            listOfNotNull(item.authorName, item.sourceLocation, item.targetLocation)
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
) {
    val dutyDetail = state.dutyDetail
    val communityDetail = state.communityDetail
    val detailError = state.detailError
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
                    Text(detailError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retryDetail) {
                        Text(stringResource(R.string.recruitment_retry))
                    }
                }
            }

        dutyDetail != null -> DutyRecruitmentDetailContent(dutyDetail, detailError, viewModel, modifier)
        communityDetail != null ->
            CommunityRecruitmentDetailContent(communityDetail, state, detailError, viewModel, modifier)
    }
}

@Composable
private fun DutyRecruitmentDetailContent(
    detail: DutyRecruitmentDetail,
    refreshError: String?,
    viewModel: DutyRecruitmentViewModel,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
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
                Text(
                    "${detail.summary.characterName} · ${detail.summary.areaName}/${detail.summary.groupName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecruitmentChips(
                    listOf(
                        detail.summary.dutyType,
                        detail.summary.schedule,
                        detail.summary.progress,
                        detail.summary.targetAreaName,
                    ),
                )
                refreshError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
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
    refreshError: String?,
    viewModel: DutyRecruitmentViewModel,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
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
                listOfNotNull(
                    detail.summary.authorName,
                    detail.summary.sourceLocation,
                    detail.summary.targetLocation,
                ).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RecruitmentChips(detail.information.map { it.value })
                refreshError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                detail.content.forEach { content ->
                    RecruitmentTextSection(
                        stringResource(R.string.recruitment_description),
                        content.text,
                    )
                }
                detail.summary.summary?.let {
                    RecruitmentTextSection(stringResource(R.string.recruitment_summary), it)
                }
                if (state.board == RecruitmentBoardKind.RolePlay) {
                    RolePlayReadOnlyExtras(state)
                }
                TextButton(onClick = viewModel::refreshDetail) {
                    Text(stringResource(R.string.recruitment_refresh_detail))
                }
            }
        }
    }
}

@Composable
private fun RolePlayReadOnlyExtras(state: DutyRecruitmentUiState) {
    val rating = state.rating
    Text(stringResource(R.string.recruitment_roleplay_extras), fontWeight = FontWeight.SemiBold)
    when {
        state.isLoadingMembers || state.isLoadingReviews || state.isLoadingRating ->
            Text(stringResource(R.string.recruitment_loading))
    }
    rating?.let {
        Text(
            stringResource(
                R.string.recruitment_rating,
                it.averageScore,
                it.totalCount,
            ),
        )
    }
    if (state.members.isNotEmpty()) {
        RecruitmentChips(state.members.map { it.name })
    }
    state.reviewsError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.reviews.forEach { review ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(review.authorName, fontWeight = FontWeight.Medium)
            Text(review.content, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.recruitment_likes, review.likeCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
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
