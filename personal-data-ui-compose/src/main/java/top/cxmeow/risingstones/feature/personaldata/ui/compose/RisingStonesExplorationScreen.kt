package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesExplorationScreen(
    viewModel: ExplorationViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shareAction = LocalPersonalDataShareAction.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phantom by viewModel.phantomWeapons.collectAsStateWithLifecycle()
    val navigateBack = {
        if (!viewModel.returnToPhantomWeapons()) {
            if (phantom.isOpen) viewModel.closePhantomWeapons() else onNavigateBack()
        }
    }
    BackHandler(onBack = navigateBack)
    // Keep the five stage anchors outside the layout branches and history composition.
    val weaponScroll = key(phantom.contentGeneration) {
        val anchors = PhantomWeaponStage.entries.associateWith { stage -> key(stage) { rememberLazyListState() } }
        anchors[phantom.selectedStage] ?: rememberLazyListState()
    }
    val sectionScroll = key(phantom.contentGeneration) {
        state.board.sections().associateWith { kind -> key(kind) { rememberLazyListState() } }.getValue(state.selectedSection)
    }
    Scaffold(modifier, topBar = {
        TopAppBar(title = { Text(stringResource(state.board.label())) }, navigationIcon = {
            TextButton(onClick = navigateBack, modifier = Modifier.testTag("exploration-back")) { Text(stringResource(R.string.personal_data_back)) }
        }, actions = {
            if (shareAction != null) TextButton(onClick = shareAction, modifier = Modifier.testTag("personal-data-share")) {
                Text(stringResource(R.string.pds_share))
            }
            TextButton(onClick = viewModel::refresh, enabled = !state.isLoadingOverview && !state.isLoadingHistory,
                modifier = Modifier.testTag("exploration-refresh")) {
                Text(stringResource(R.string.personal_data_refresh))
            }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val mode = personalDataLayoutFromPixels(constraints.maxWidth, LocalDensity.current.density)
            val unavailable = state.error in listOf(ExplorationError.AuthenticationRequired, ExplorationError.Unavailable)
            if (unavailable || state.overview?.available == false) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (unavailable) R.string.exploration_sign_in else R.string.exploration_closed),
                        modifier = Modifier.testTag("exploration-unavailable"))
                }
            } else if (mode == RisingStonesPersonalDataLayoutMode.Compact) {
                Column(Modifier.fillMaxSize().testTag("exploration-compact")) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (viewModel.supportsPhantomWeapons) item { PhantomChoice(state, phantom, viewModel) }
                        items(state.board.sections()) { SectionChoice(state, it, viewModel::selectSection, isPhantomOpen = phantom.isOpen) }
                    }
                    if (phantom.isOpen) PhantomWeaponPane(phantom, viewModel, weaponScroll, Modifier.fillMaxSize())
                    else ExplorationContent(state, viewModel::refresh, sectionScroll, Modifier.fillMaxSize())
                }
            } else Row(Modifier.fillMaxSize().testTag("exploration-wide")) {
                LazyColumn(Modifier.width(if (mode == RisingStonesPersonalDataLayoutMode.Medium) 220.dp else 280.dp)
                    .fillMaxHeight(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.supportsPhantomWeapons) item { PhantomChoice(state, phantom, viewModel, Modifier.fillMaxWidth()) }
                    items(state.board.sections()) { SectionChoice(state, it, viewModel::selectSection, Modifier.fillMaxWidth(), phantom.isOpen) }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    if (phantom.isOpen) PhantomWeaponPane(phantom, viewModel, weaponScroll, Modifier.widthIn(max = 840.dp).fillMaxSize())
                    else ExplorationContent(state, viewModel::refresh, sectionScroll, Modifier.widthIn(max = 840.dp).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PhantomChoice(state: ExplorationUiState, phantom: PhantomWeaponUiState,
    model: ExplorationViewModel, modifier: Modifier = Modifier) {
    FilterChip(phantom.isOpen, model::openPhantomWeapons, enabled = state.overview?.available == true,
        label = { Text(stringResource(R.string.pdw_title)) }, modifier = modifier.testTag("exploration-phantom"))
}

@Composable
private fun SectionChoice(state: ExplorationUiState, kind: ExplorationSectionKind,
    onSelect: (ExplorationSectionKind) -> Unit, modifier: Modifier = Modifier, isPhantomOpen: Boolean = false) {
    FilterChip(selected = !isPhantomOpen && kind == state.selectedSection, onClick = { onSelect(kind) },
        enabled = state.overview?.available == true,
        label = { Text(stringResource(kind.label())) }, modifier = modifier.testTag("exploration-section-$kind"))
}

@Composable
private fun ExplorationContent(state: ExplorationUiState, onRetry: () -> Unit, scroll: LazyListState, modifier: Modifier) {
    // Parent anchors survive weapon and history navigation as well as width changes.
    key(state.selectedSection) {
        LazyColumn(modifier.testTag("exploration-content-${state.selectedSection}"), state = scroll, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(state.selectedSection.label()), style = MaterialTheme.typography.headlineSmall) }
            if (state.isLoadingOverview || state.isLoadingHistory) item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("exploration-loading"))
            }
            if (state.error != null || state.section?.failure != null) item {
                Column(Modifier.testTag("exploration-error")) {
                    Text(stringResource(R.string.exploration_failure), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.personal_data_retry)) }
                }
            }
            val section = state.section
            if (section != null && section.failure == null && section.records.isEmpty() && !state.isLoadingHistory) item {
                Text(stringResource(R.string.exploration_empty), Modifier.testTag("exploration-empty"))
            }
            items(section?.records.orEmpty(), key = ExplorationRecord::key) { record -> ExplorationRecordCard(record, state.selectedSection) }
            item { Text(stringResource(R.string.exploration_source), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ExplorationRecordCard(record: ExplorationRecord, kind: ExplorationSectionKind) {
    val phantomId = record.fields.firstOrNull { it.kind == ExplorationFieldKind.PhantomJob }?.value?.toIntOrNull()
    val itemId = record.itemId
    val achievementId = record.achievementId
    val territoryId = record.territoryId
    val title = when {
        phantomId != null -> phantomJobName(phantomId)
        record.title.isNotBlank() -> record.title
        itemId != null -> stringResource(R.string.exploration_item_number, itemId)
        achievementId != null -> stringResource(R.string.exploration_achievement_number, achievementId)
        territoryId != null && record.fields.none { it.kind == ExplorationFieldKind.Floor } ->
            stringResource(R.string.exploration_territory_number, territoryId)
        else -> null
    }
    Card(Modifier.fillMaxWidth().testTag("exploration-record-${record.key}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            val level = record.fields.firstOrNull { it.kind == ExplorationFieldKind.Level }?.value?.toIntOrNull()
            if (phantomId != null && level != null) {
                val cap = PhantomJobCatalog.levelCaps[phantomId]
                if (phantomId == 0) Text(stringResource(R.string.exploration_mastered, level))
                else if (cap != null && cap > 0) {
                    Text(stringResource(R.string.exploration_level_progress, level, cap))
                    LinearProgressIndicator(progress = { (level.toFloat() / cap).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
            record.fields.filterNot { field ->
                (field.kind == ExplorationFieldKind.ItemName || field.kind == ExplorationFieldKind.AchievementName) && field.value == title ||
                    phantomId != null && field.kind == ExplorationFieldKind.PhantomJob ||
                    phantomId != null && level != null && PhantomJobCatalog.levelCaps[phantomId] != null && field.kind == ExplorationFieldKind.Level
            }.forEach { field ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(field.kind.label()), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fieldValue(field), Modifier.weight(1f))
                }
            }
            if (record.fields.isEmpty() && title == null) Text(stringResource(kind.label()))
        }
    }
}

@Composable
private fun phantomJobName(id: Int): String = stringResource(phantomJobLabels.getOrNull(id) ?: R.string.exploration_unknown)

@Composable
private fun fieldValue(field: ExplorationField): String = when (field.kind) {
    ExplorationFieldKind.Solo -> when (field.value) {
        "1" -> stringResource(R.string.exploration_solo_value)
        "0" -> stringResource(R.string.exploration_party_value)
        else -> stringResource(R.string.exploration_unknown)
    }
    ExplorationFieldKind.AetherColor -> stringResource(when (field.value) {
        "green" -> R.string.exploration_green
        "blue" -> R.string.exploration_blue
        "red" -> R.string.exploration_red
        "yellow" -> R.string.exploration_yellow
        else -> R.string.exploration_unknown
    })
    ExplorationFieldKind.BoxGrade -> stringResource(when (field.value) {
        "copper" -> R.string.exploration_copper
        "silver" -> R.string.exploration_silver
        "gold" -> R.string.exploration_gold
        else -> R.string.exploration_unknown
    })
    ExplorationFieldKind.ClassJob -> field.value.toIntOrNull()?.let { id ->
        stringResource(classJobLabels[id] ?: R.string.exploration_unknown)
    } ?: field.value
    ExplorationFieldKind.ClearDuration -> field.value.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
        stringResource(R.string.exploration_duration, seconds / 3600, seconds / 60 % 60, seconds % 60)
    } ?: stringResource(R.string.exploration_unknown)
    else -> field.value
}

internal fun ExplorationBoard.label(): Int = when (this) {
    ExplorationBoard.OccultCrescent -> R.string.exploration_occult
    ExplorationBoard.DeepDungeon -> R.string.exploration_deep
}
