package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriod
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriodKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataField
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetric
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetricUnit
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDashboard
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDeathPoint
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterCatalog
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterDetail
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterSummary
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUiState
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesPersonalDataScreen(
    service: PersonalDataService,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PersonalDataViewModel = viewModel(
        key = "rising-stones-personal-data",
        factory = remember(service) { PersonalDataViewModelFactory(service) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.personal_data_back))
                    }
                },
                title = { Text(stringResource(R.string.personal_data_title)) },
                actions = {
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = viewModel.hasCommunityIdentity &&
                            !state.isLoadingRoot &&
                            !state.isLoadingBoard &&
                            !state.isLoadingDetail,
                    ) {
                        Text(stringResource(R.string.personal_data_refresh))
                    }
                },
            )
        },
    ) { contentPadding ->
        if (!viewModel.hasCommunityIdentity) {
            PersonalDataCenteredMessage(
                text = stringResource(R.string.personal_data_identity_required),
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
            when (val layout = risingStonesPersonalDataLayoutMode(maxWidth.value.toInt())) {
                RisingStonesPersonalDataLayoutMode.Compact -> {
                    PersonalDataCompactContent(state, viewModel)
                }

                RisingStonesPersonalDataLayoutMode.Medium,
                RisingStonesPersonalDataLayoutMode.Expanded,
                -> {
                    Row(Modifier.fillMaxSize()) {
                        PersonalDataHubPane(
                            state = state,
                            onSelectBoard = viewModel::selectBoard,
                            onRetryRoot = { viewModel.loadRoot(force = true) },
                            modifier = Modifier
                                .width(
                                    if (layout == RisingStonesPersonalDataLayoutMode.Expanded) {
                                        360.dp
                                    } else {
                                        300.dp
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
                                .fillMaxHeight()
                                .testTag("personal-data-detail-pane"),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            PersonalDataSelectedContent(
                                state = state,
                                viewModel = viewModel,
                                showBoardBack = false,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .widthIn(max = 1_120.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalDataCompactContent(
    state: PersonalDataUiState,
    viewModel: PersonalDataViewModel,
) {
    BackHandler(enabled = state.selectedBoard != null) {
        if (state.selectedEncounter != null) {
            viewModel.clearEncounterSelection()
        } else {
            viewModel.clearBoardSelection()
        }
    }

    if (state.selectedBoard == null) {
        PersonalDataHubPane(
            state = state,
            onSelectBoard = viewModel::selectBoard,
            onRetryRoot = { viewModel.loadRoot(force = true) },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        PersonalDataSelectedContent(
            state = state,
            viewModel = viewModel,
            showBoardBack = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PersonalDataHubPane(
    state: PersonalDataUiState,
    onSelectBoard: (PersonalDataBoard) -> Unit,
    onRetryRoot: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("personal-data-hub-pane"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PersonalDataIdentityCard(
                identity = state.identity,
                isLoading = state.isLoadingRoot,
                error = state.rootError,
                onRetry = onRetryRoot,
            )
        }
        item {
            Text(
                text = stringResource(R.string.personal_data_boards),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(PersonalDataBoard.entries, key = PersonalDataBoard::name) { board ->
            PersonalDataBoardCard(
                board = board,
                selected = state.selectedBoard == board,
                availability = state.availability,
                onClick = { onSelectBoard(board) },
            )
        }
        item {
            Text(
                text = stringResource(R.string.personal_data_source_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PersonalDataIdentityCard(
    identity: PersonalDataIdentity?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PersonalDataAvatar(identity)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = identity?.characterName
                            ?: stringResource(R.string.personal_data_loading_identity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = identity?.location?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.personal_data_identity_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.personal_data_retry))
                }
            }
        }
    }
}

@Composable
private fun PersonalDataAvatar(identity: PersonalDataIdentity?) {
    val modifier = Modifier
        .size(52.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (identity?.avatarUrl.isNullOrBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = identity?.characterName?.take(1)?.uppercase().orEmpty().ifBlank { "?" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        AsyncImage(
            model = identity.avatarUrl,
            contentDescription = identity.characterName,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun PersonalDataBoardCard(
    board: PersonalDataBoard,
    selected: Boolean,
    availability: PersonalDataAvailability?,
    onClick: () -> Unit,
) {
    val hasData = availability?.hasData(board)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("personal-data-board-${board.name}"),
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
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = personalDataBoardMark(board),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = personalDataBoardTitle(board),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (hasData) {
                        true -> stringResource(R.string.personal_data_available)
                        false -> stringResource(R.string.personal_data_no_record)
                        null -> stringResource(R.string.personal_data_status_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasData == false) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun PersonalDataSelectedContent(
    state: PersonalDataUiState,
    viewModel: PersonalDataViewModel,
    showBoardBack: Boolean,
    modifier: Modifier,
) {
    when {
        state.selectedBoard == null -> PersonalDataCenteredMessage(
            text = stringResource(R.string.personal_data_select_board),
            modifier = modifier,
        )

        state.selectedEncounter != null -> PersonalDataEncounterDetailPane(
            state = state,
            onBack = viewModel::clearEncounterSelection,
            onRetry = { viewModel.loadEncounterDetail(force = true) },
            modifier = modifier,
        )

        else -> PersonalDataBoardPane(
            state = state,
            onBack = viewModel::clearBoardSelection,
            showBack = showBoardBack,
            onRetry = { viewModel.loadBoard(force = true) },
            onSelectEncounter = viewModel::selectEncounter,
            modifier = modifier,
        )
    }
}

@Composable
private fun PersonalDataBoardPane(
    state: PersonalDataUiState,
    onBack: () -> Unit,
    showBack: Boolean,
    onRetry: () -> Unit,
    onSelectEncounter: (UltimateEncounterSummary) -> Unit,
    modifier: Modifier,
) {
    val board = state.selectedBoard ?: return
    LazyColumn(
        modifier = modifier.testTag("personal-data-board-pane"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            if (showBack) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.personal_data_back_to_boards))
                }
            }
            PersonalDataBoardHeader(
                board = board,
                hasData = state.availability?.hasData(board),
            )
        }

        if (state.isLoadingBoard && state.content == null && state.dashboard == null) {
            item {
                PersonalDataLoading(
                    text = stringResource(R.string.personal_data_loading_board),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
        } else if (
            state.boardError != null &&
            state.content == null &&
            state.dashboard == null
        ) {
            item {
                PersonalDataRecoverableMessage(
                    message = state.boardError,
                    onRetry = onRetry,
                )
            }
        } else if (board == PersonalDataBoard.Ultimate) {
            state.dashboard?.let { dashboard ->
                ultimateDashboardItems(dashboard, onSelectEncounter)
            }
        } else {
            state.content?.let(::boardContentItems)
        }

        if (
            !state.isLoadingBoard &&
            state.boardError == null &&
            state.content == null &&
            state.dashboard == null
        ) {
            item {
                PersonalDataCenteredMessage(
                    text = stringResource(R.string.personal_data_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                )
            }
        }

        state.boardError?.takeIf { state.content != null || state.dashboard != null }?.let {
            item { PersonalDataInlineError(it, onRetry) }
        }
    }
}

@Composable
private fun PersonalDataBoardHeader(
    board: PersonalDataBoard,
    hasData: Boolean?,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = personalDataBoardTitle(board),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = personalDataBoardSubtitle(board),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (hasData == false) {
                Text(
                    text = stringResource(R.string.personal_data_board_no_record),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ultimateDashboardItems(
    dashboard: UltimateDashboard,
    onSelectEncounter: (UltimateEncounterSummary) -> Unit,
) {
    item {
        PersonalDataMetricGrid(
            metrics = listOf(
                PersonalDataMetric("ultimate_cleared", dashboard.summaries.size.toString()),
                PersonalDataMetric("ultimate_total_clears", dashboard.totalClears.toString()),
            ),
        )
    }
    item {
        Text(
            text = stringResource(R.string.personal_data_ultimate_encounters),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
    items(UltimateEncounterCatalog.encounters, key = { it.territoryType }) { encounter ->
        val summary = dashboard.summary(encounter.territoryType)
        PersonalDataEncounterCard(
            encounter = encounter,
            summary = summary,
            onClick = summary?.let { { onSelectEncounter(it) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.boardContentItems(
    content: PersonalDataBoardContent,
) {
    if (content.frontlinePeriods.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.personal_data_periods),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        items(content.frontlinePeriods, key = { it.kind.name }) { period ->
            PersonalDataPeriodCard(period)
        }
    } else if (content.metrics.isNotEmpty()) {
        item { PersonalDataMetricGrid(content.metrics) }
    }

    if (content.sections.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.personal_data_details),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        items(content.sections, key = PersonalDataSection::id) { section ->
            PersonalDataSectionCard(section)
        }
    }
}

@Composable
private fun PersonalDataPeriodCard(period: FrontlinePeriod) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when (period.kind) {
                    FrontlinePeriodKind.Total ->
                        stringResource(R.string.personal_data_period_total)
                    FrontlinePeriodKind.Since51 ->
                        stringResource(R.string.personal_data_period_since_51)
                    FrontlinePeriodKind.Last30Days ->
                        stringResource(R.string.personal_data_period_last_30)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            PersonalDataMetricGrid(period.metrics)
        }
    }
}

@Composable
private fun PersonalDataMetricGrid(metrics: List<PersonalDataMetric>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowMetrics.forEach { metric ->
                    PersonalDataMetricCard(metric, Modifier.weight(1f))
                }
                if (rowMetrics.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PersonalDataMetricCard(
    metric: PersonalDataMetric,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = personalDataMetricValue(metric),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = personalDataMetricLabel(metric.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PersonalDataSectionCard(section: PersonalDataSection) {
    var expanded by rememberSaveable(section.id, section.entries.size) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val visibleEntries = if (expanded) section.entries else section.entries.take(3)
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = personalDataSectionTitle(section.id),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            section.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (visibleEntries.isEmpty() && section.error == null) {
                Text(
                    text = stringResource(R.string.personal_data_section_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            visibleEntries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                PersonalDataEntryContent(entry, index)
            }
            if (section.entries.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.personal_data_show_less)
                        } else {
                            stringResource(
                                R.string.personal_data_show_all,
                                section.entries.size,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalDataEntryContent(entry: PersonalDataEntry, index: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = entry.title.takeIf(String::isNotBlank)
                ?: stringResource(R.string.personal_data_entry_number, index + 1),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        entry.fields.forEach { field ->
            PersonalDataFieldRow(field)
        }
    }
}

@Composable
private fun PersonalDataFieldRow(field: PersonalDataField) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = humanizeKey(field.key),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = field.value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
private fun PersonalDataEncounterCard(
    encounter: top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounter,
    summary: UltimateEncounterSummary?,
    onClick: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("personal-data-encounter-${encounter.territoryType}")
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (summary != null) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = encounter.coverUrl,
                contentDescription = encounter.title,
                modifier = Modifier
                    .width(112.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = encounter.shortName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = encounter.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary?.let {
                        stringResource(R.string.personal_data_clear_count, it.clearTimes)
                    } ?: stringResource(R.string.personal_data_not_cleared),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (summary != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (onClick != null) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun PersonalDataEncounterDetailPane(
    state: PersonalDataUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val summary = state.selectedEncounter ?: return
    val encounter = UltimateEncounterCatalog.find(summary.territoryType)
    LazyColumn(
        modifier = modifier.testTag("personal-data-encounter-detail"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.personal_data_back_to_ultimate))
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onRetry,
                    enabled = !state.isLoadingDetail,
                ) {
                    Text(stringResource(R.string.personal_data_refresh_detail))
                }
            }
        }
        item {
            Card {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 280.dp),
                ) {
                    encounter?.let {
                        AsyncImage(
                            model = it.coverUrl,
                            contentDescription = it.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f))
                            .padding(16.dp),
                    ) {
                        Text(
                            text = encounter?.shortName
                                ?: summary.territoryType.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        encounter?.let {
                            Text(
                                text = it.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                    }
                }
            }
        }
        item {
            PersonalDataMetricGrid(
                listOf(
                    PersonalDataMetric("clear_times", summary.clearTimes.toString()),
                    PersonalDataMetric(
                        "enters_before_first_clear",
                        summary.entersBeforeFirstClear?.toString() ?: "—",
                    ),
                    PersonalDataMetric(
                        "deaths_before_first_clear",
                        summary.deathsBeforeFirstClear?.toString() ?: "—",
                    ),
                    PersonalDataMetric(
                        "first_clear_elapsed",
                        summary.firstClearElapsedSeconds?.let(::formatDuration) ?: "—",
                    ),
                ),
            )
        }
        item {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = stringResource(R.string.personal_data_first_clear),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    PersonalDataLabelValue(
                        stringResource(R.string.personal_data_job),
                        summary.firstClearJobName ?: "—",
                    )
                    PersonalDataLabelValue(
                        stringResource(R.string.personal_data_time),
                        summary.firstClearAt?.let(::formatInstant) ?: "—",
                    )
                }
            }
        }

        if (state.isLoadingDetail && state.encounterDetail == null) {
            item {
                PersonalDataLoading(
                    text = stringResource(R.string.personal_data_loading_detail),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
        } else if (state.detailError != null && state.encounterDetail == null) {
            item { PersonalDataRecoverableMessage(state.detailError, onRetry) }
        } else {
            state.encounterDetail?.let { detail ->
                encounterDetailItems(detail)
            }
        }
        state.detailError?.takeIf { state.encounterDetail != null }?.let {
            item { PersonalDataInlineError(it, onRetry) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.encounterDetailItems(
    detail: UltimateEncounterDetail,
) {
    item {
        PersonalDataDetailSection(
            title = stringResource(R.string.personal_data_teammates),
            error = detail.sectionErrors["team"],
        ) {
            detail.teammates.forEach {
                PersonalDataLabelValue(
                    it.characterName,
                    listOf(it.jobName, it.areaName, it.groupName)
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                )
            }
        }
    }
    item {
        PersonalDataDetailSection(
            title = stringResource(R.string.personal_data_jobs),
            error = detail.sectionErrors["jobs"],
        ) {
            detail.jobs.forEach {
                PersonalDataLabelValue(it.jobName, it.clearTimes.toString())
            }
        }
    }
    item {
        PersonalDataDetailSection(
            title = stringResource(R.string.personal_data_partners),
            error = detail.sectionErrors["partners"],
        ) {
            detail.partners.forEach {
                PersonalDataLabelValue(
                    it.characterName,
                    stringResource(
                        R.string.personal_data_joint_battles,
                        it.jointBattleTimes,
                    ),
                )
            }
        }
    }
    item {
        PersonalDataDetailSection(
            title = stringResource(R.string.personal_data_phases),
            error = detail.sectionErrors["phases"],
        ) {
            detail.phases.forEach {
                PersonalDataLabelValue(
                    it.phase,
                    it.reachedAt?.let(::formatInstant) ?: "—",
                )
            }
        }
    }
    item {
        PersonalDataDetailSection(
            title = stringResource(R.string.personal_data_deaths),
            error = detail.sectionErrors["deaths"],
        ) {
            detail.deathPoints.forEach { point ->
                PersonalDataDeathPointRow(point)
            }
        }
    }
}

@Composable
private fun PersonalDataDetailSection(
    title: String,
    error: String?,
    content: @Composable () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            content()
        }
    }
}

@Composable
private fun PersonalDataDeathPointRow(point: UltimateDeathPoint) {
    PersonalDataLabelValue(
        point.period.ifBlank { stringResource(R.string.personal_data_death_point) },
        stringResource(
            R.string.personal_data_death_coordinates,
            point.x,
            point.y,
            point.occurredAt?.let(::formatInstant) ?: "—",
        ),
    )
}

@Composable
private fun PersonalDataLabelValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.46f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.54f),
        )
    }
}

@Composable
private fun PersonalDataRecoverableMessage(
    message: String?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message ?: stringResource(R.string.personal_data_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.personal_data_retry))
        }
    }
}

@Composable
private fun PersonalDataInlineError(message: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.personal_data_retry))
            }
        }
    }
}

@Composable
private fun PersonalDataLoading(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PersonalDataCenteredMessage(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun personalDataBoardTitle(board: PersonalDataBoard): String = stringResource(
    when (board) {
        PersonalDataBoard.Frontline -> R.string.personal_data_board_frontline
        PersonalDataBoard.Ultimate -> R.string.personal_data_board_ultimate
        PersonalDataBoard.Fishing -> R.string.personal_data_board_fishing
        PersonalDataBoard.Savage -> R.string.personal_data_board_savage
        PersonalDataBoard.Glamour -> R.string.personal_data_board_glamour
    },
)

@Composable
private fun personalDataBoardSubtitle(board: PersonalDataBoard): String = stringResource(
    when (board) {
        PersonalDataBoard.Frontline -> R.string.personal_data_board_frontline_subtitle
        PersonalDataBoard.Ultimate -> R.string.personal_data_board_ultimate_subtitle
        PersonalDataBoard.Fishing -> R.string.personal_data_board_fishing_subtitle
        PersonalDataBoard.Savage -> R.string.personal_data_board_savage_subtitle
        PersonalDataBoard.Glamour -> R.string.personal_data_board_glamour_subtitle
    },
)

private fun personalDataBoardMark(board: PersonalDataBoard): String = when (board) {
    PersonalDataBoard.Frontline -> "PvP"
    PersonalDataBoard.Ultimate -> "ULT"
    PersonalDataBoard.Fishing -> "FIS"
    PersonalDataBoard.Savage -> "SAV"
    PersonalDataBoard.Glamour -> "GLM"
}

@Composable
private fun personalDataMetricLabel(id: String): String = when (id) {
    "fight_times" -> stringResource(R.string.personal_data_metric_battles)
    "kda" -> stringResource(R.string.personal_data_metric_kda)
    "kill_times" -> stringResource(R.string.personal_data_metric_kills)
    "win_rate" -> stringResource(R.string.personal_data_metric_win_rate)
    "gc_id" -> stringResource(R.string.personal_data_metric_grand_company)
    "pvp_rank" -> stringResource(R.string.personal_data_metric_pvp_rank)
    "series_level" -> stringResource(R.string.personal_data_metric_series_level)
    "win_times" -> stringResource(R.string.personal_data_metric_wins)
    "assist_times" -> stringResource(R.string.personal_data_metric_assists)
    "dead_times" -> stringResource(R.string.personal_data_metric_deaths)
    "total_times" -> stringResource(R.string.personal_data_metric_total_catches)
    "succ_rate" -> stringResource(R.string.personal_data_metric_success_rate)
    "sea_times" -> stringResource(R.string.personal_data_metric_ocean_trips)
    "max_sea_score" -> stringResource(R.string.personal_data_metric_ocean_score)
    "territory_num" -> stringResource(R.string.personal_data_metric_raids)
    "enter_num" -> stringResource(R.string.personal_data_metric_entries)
    "finish_times" -> stringResource(R.string.personal_data_metric_clears)
    "elapsed_time" -> stringResource(R.string.personal_data_metric_elapsed)
    "washing_num" -> stringResource(R.string.personal_data_metric_washings)
    "color_times" -> stringResource(R.string.personal_data_metric_dyes)
    "vanity_times" -> stringResource(R.string.personal_data_metric_glamours)
    "ultimate_cleared" -> stringResource(R.string.personal_data_metric_ultimate_cleared)
    "ultimate_total_clears" -> stringResource(R.string.personal_data_metric_ultimate_clears)
    "clear_times" -> stringResource(R.string.personal_data_metric_clears)
    "enters_before_first_clear" ->
        stringResource(R.string.personal_data_metric_entries_before_clear)
    "deaths_before_first_clear" ->
        stringResource(R.string.personal_data_metric_deaths_before_clear)
    "first_clear_elapsed" -> stringResource(R.string.personal_data_metric_first_clear_elapsed)
    else -> humanizeKey(id)
}

@Composable
private fun personalDataSectionTitle(id: String): String = when (id) {
    "weekly" -> stringResource(R.string.personal_data_section_weekly)
    "job" -> stringResource(R.string.personal_data_section_jobs)
    "best" -> stringResource(R.string.personal_data_section_best)
    "map" -> stringResource(R.string.personal_data_section_maps)
    "mapJob" -> stringResource(R.string.personal_data_section_map_jobs)
    "fish" -> stringResource(R.string.personal_data_section_fish)
    "bait" -> stringResource(R.string.personal_data_section_bait)
    "bigFish" -> stringResource(R.string.personal_data_section_big_fish)
    "achievement" -> stringResource(R.string.personal_data_section_achievements)
    "territory" -> stringResource(R.string.personal_data_section_raids)
    "race" -> stringResource(R.string.personal_data_section_races)
    "color" -> stringResource(R.string.personal_data_section_colors)
    "ornament" -> stringResource(R.string.personal_data_section_ornaments)
    "vanity" -> stringResource(R.string.personal_data_section_vanity)
    "fullset" -> stringResource(R.string.personal_data_section_full_sets)
    else -> humanizeKey(id)
}

@Composable
private fun personalDataMetricValue(metric: PersonalDataMetric): String {
    val unit = when (metric.unit) {
        PersonalDataMetricUnit.Times -> stringResource(R.string.personal_data_unit_times)
        PersonalDataMetricUnit.Percent -> stringResource(R.string.personal_data_unit_percent)
        PersonalDataMetricUnit.Levels -> stringResource(R.string.personal_data_unit_levels)
        PersonalDataMetricUnit.Points -> stringResource(R.string.personal_data_unit_points)
        PersonalDataMetricUnit.Pieces -> stringResource(R.string.personal_data_unit_pieces)
        PersonalDataMetricUnit.Hours -> stringResource(R.string.personal_data_unit_hours)
        null -> ""
    }
    return if (unit.isBlank()) metric.value else "${metric.value} $unit"
}

private fun humanizeKey(value: String): String = value
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .replace('.', ' ')
    .replace('_', ' ')
    .trim()
    .split(Regex("\\s+"))
    .joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.titlecase() }
    }

private fun formatInstant(instant: Instant): String = PersonalDataDateFormatter.format(instant)

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainingSeconds = seconds % 60
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { "${it}h" },
        minutes.takeIf { it > 0 || hours > 0 }?.let { "${it}m" },
        "${remainingSeconds}s",
    ).joinToString(" ")
}

private val PersonalDataDateFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
