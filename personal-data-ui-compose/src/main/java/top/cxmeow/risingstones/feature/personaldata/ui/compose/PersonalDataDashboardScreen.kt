package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

internal val LocalPersonalDataDashboardPages = staticCompositionLocalOf<SaveableStateHolder?> { null }

/** Optional content pane; the host owns top-level navigation and the surrounding app bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesPersonalDataDashboardPane(
    viewModel: PersonalDataDashboardViewModel,
    onNavigateBack: () -> Unit,
    onOpenReading: (PersonalDataReadingPage) -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    itemIconUrl: (Int) -> String? = { null },
    achievementIconUrl: (Int) -> String? = { null },
    raidImageUrl: (Int) -> String? = { null },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val board = state.activeBoard ?: return
    val kind = state.selectedSection ?: return
    val section = state.currentSection
    if (!viewModel.hasCommunityIdentity || section.status == PersonalDataDashboardLoadStatus.AuthRequired) {
        Text(stringResource(R.string.pdr_auth), modifier.padding(24.dp).testTag("dashboard-auth"))
        return
    }
    val savedPages = LocalPersonalDataDashboardPages.current ?: rememberSaveableStateHolder()
    savedPages.SaveableStateProvider(kind.name) {
        val scroll = rememberLazyListState()
        val criteria = state.currentCriteria.copy(visibleLimit = 0)
        var previousCriteria by rememberSaveable { mutableStateOf(criteria.toString()) }
        LaunchedEffect(criteria) {
            if (previousCriteria != criteria.toString()) {
                scroll.scrollToItem(0)
                previousCriteria = criteria.toString()
            }
        }
        LazyColumn(modifier.fillMaxSize().testTag("dashboard-list"), state = scroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (showBack) TextButton(onNavigateBack, Modifier.testTag("dashboard-back")) {
                    Text(stringResource(R.string.personal_data_back_to_boards))
                }
                Text(stringResource(when (board) {
                    PersonalDataBoard.Fishing -> R.string.personal_data_board_fishing
                    PersonalDataBoard.Glamour -> R.string.personal_data_board_glamour
                    else -> R.string.personal_data_board_savage
                }), style = MaterialTheme.typography.headlineSmall)
            }
            item {
                val sections = PersonalDataDashboardSectionKind.forBoard(board)
                PrimaryScrollableTabRow(selectedTabIndex = sections.indexOf(kind), edgePadding = 0.dp) {
                    sections.forEach {
                        Tab(it == kind, { viewModel.selectSection(it) },
                            text = { Text(stringResource(it.dashboardLabel())) },
                            modifier = Modifier.testTag("dashboard-section-$it"))
                    }
                }
            }
            item { DashboardFilters(state, viewModel) }
            item { DashboardDirectoryState(state, viewModel::retryCatalogs) }
            if (section.status == PersonalDataDashboardLoadStatus.Loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dashboard-loading"))
                Text(stringResource(R.string.pdr_loading))
            }
            if (section.failure != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.pdr_failure), Modifier.testTag("dashboard-error"))
                        OutlinedButton({ viewModel.retrySection(kind) }, Modifier.testTag("dashboard-retry")) {
                            Text(stringResource(R.string.pdr_retry))
                        }
                    }
                }
            }
            section.data?.let { data ->
                dashboardContentItems(data, state, viewModel::showMore, onOpenReading, itemIconUrl, achievementIconUrl, raidImageUrl)
            }
        }
    }
}

@Composable
private fun DashboardFilters(state: PersonalDataDashboardUiState, model: PersonalDataDashboardViewModel) {
    val kind = state.selectedSection ?: return
    if (kind in listOf(PersonalDataDashboardSectionKind.FishingSummary, PersonalDataDashboardSectionKind.GlamourSummary,
            PersonalDataDashboardSectionKind.SavageSummary, PersonalDataDashboardSectionKind.OceanFishing)) return
    val criteria = state.currentCriteria
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(criteria.query, model::updateQuery, Modifier.fillMaxWidth().testTag("dashboard-search"),
            singleLine = true, label = { Text(stringResource(R.string.pdr_search)) })
        if (kind in listOf(PersonalDataDashboardSectionKind.FishRanking, PersonalDataDashboardSectionKind.BaitRanking)) {
            val rows = when (val data = state.currentSection.data) {
                is PersonalDataDashboardData.FishRanking -> data.rows
                is PersonalDataDashboardData.BaitRanking -> data.rows
                else -> emptyList()
            }
            DashboardChips {
                FilterChip(criteria.category == null, { model.selectCategory(null) }, { Text(stringResource(R.string.pdr_all)) }, Modifier.testTag("dashboard-category-all"))
                rows.map { it.category.orEmpty() }.distinct().forEachIndexed { index, category ->
                    FilterChip(criteria.category == category, { model.selectCategory(category) }, { Text(dashboardCategoryLabel(category)) }, Modifier.testTag("dashboard-category-$index"))
                }
            }
        }
        if (kind == PersonalDataDashboardSectionKind.BigFish) {
            DashboardChips {
                PersonalDataDashboardFishGroup.entries.forEach { group ->
                    FilterChip(criteria.fishGroup == group, { model.selectFishGroup(group) },
                        { Text(stringResource(if (group == PersonalDataDashboardFishGroup.Kings) R.string.pdd_kings else R.string.pdd_ocean_fish)) },
                        Modifier.testTag("dashboard-fish-group-$group"))
                }
            }
            if (criteria.fishGroup == PersonalDataDashboardFishGroup.Kings) DashboardChips {
                FilterChip(criteria.patch == null, { model.selectPatch(null) }, { Text(stringResource(R.string.pdr_all)) }, Modifier.testTag("dashboard-patch-all"))
                state.catalogs?.fish.orEmpty().values.map { it.patch }.distinct().sorted().forEachIndexed { index, patch ->
                    FilterChip(criteria.patch == patch, { model.selectPatch(patch) }, { Text(stringResource(R.string.pdd_patch, patch)) }, Modifier.testTag("dashboard-patch-$index"))
                }
            }
            DashboardChips {
                PersonalDataDashboardFishSort.entries.forEach { sort ->
                    FilterChip(criteria.fishSort == sort, { model.setFishSort(sort) },
                        { Text(stringResource(if (sort == PersonalDataDashboardFishSort.Newest) R.string.pdr_newest else R.string.pdd_count_order)) },
                        Modifier.testTag("dashboard-fish-sort-$sort"))
                }
            }
        }
        if (kind == PersonalDataDashboardSectionKind.BigFish || kind == PersonalDataDashboardSectionKind.FishingAchievements) {
            DashboardChips {
                FilterChip(!criteria.includeUnobtained, { model.setIncludeUnobtained(false) }, { Text(stringResource(R.string.pdd_with_records)) }, Modifier.testTag("dashboard-obtained"))
                FilterChip(criteria.includeUnobtained, { model.setIncludeUnobtained(true) }, { Text(stringResource(R.string.pdd_include_unobtained)) }, Modifier.testTag("dashboard-include-unobtained"))
            }
        }
        if (kind == PersonalDataDashboardSectionKind.Vanity) {
            val rows = (state.currentSection.data as? PersonalDataDashboardData.Vanity)?.rows.orEmpty()
            DashboardChips {
                PersonalDataVanityPeriod.entries.filter { it != PersonalDataVanityPeriod.Unknown || rows.any { row -> row.period == it } }.forEach { period ->
                    FilterChip(criteria.vanityPeriod == period, { model.setVanityPeriod(period) }, { Text(stringResource(when (period) {
                        PersonalDataVanityPeriod.AllTime -> R.string.personal_data_period_total
                        PersonalDataVanityPeriod.LastYear -> R.string.pdd_last_year
                        PersonalDataVanityPeriod.Unknown -> R.string.pdd_unknown_period
                    })) }, Modifier.testTag("dashboard-period-$period"))
                }
            }
            DashboardChips {
                listOf(1 to R.string.pdd_weapons, 3 to R.string.pdd_armor, 4 to R.string.pdd_accessories).forEach { (id, label) ->
                    FilterChip(criteria.vanityMajor == id, { model.selectVanityMajor(id) }, { Text(stringResource(label)) }, Modifier.testTag("dashboard-major-$id"))
                }
            }
            DashboardChips {
                FilterChip(criteria.vanityCategoryId == null, { model.selectVanityCategory(null) }, { Text(stringResource(R.string.pdr_all)) }, Modifier.testTag("dashboard-vanity-all"))
                PersonalDataDashboardCatalogViews.vanityCategories(rows, state.supplementary?.vanityCategories.orEmpty(), criteria.vanityPeriod, criteria.vanityMajor).forEach { category ->
                    FilterChip(criteria.vanityCategoryId == category.id, { model.selectVanityCategory(category.id) }, { Text(category.name) }, Modifier.testTag("dashboard-vanity-category-${category.id}"))
                }
            }
        }
    }
}

@Composable
private fun DashboardChips(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun DashboardDirectoryState(state: PersonalDataDashboardUiState, retry: () -> Unit) {
    val kind = state.selectedSection
    val needsBase = kind in listOf(PersonalDataDashboardSectionKind.Sets, PersonalDataDashboardSectionKind.Stains,
        PersonalDataDashboardSectionKind.Accessories, PersonalDataDashboardSectionKind.SavageRaids, PersonalDataDashboardSectionKind.GlamourSummary) ||
        kind == PersonalDataDashboardSectionKind.BigFish && state.currentCriteria.fishGroup == PersonalDataDashboardFishGroup.Kings
    val needsExtra = kind in listOf(PersonalDataDashboardSectionKind.FishingAchievements, PersonalDataDashboardSectionKind.Vanity) ||
        kind == PersonalDataDashboardSectionKind.BigFish && state.currentCriteria.fishGroup == PersonalDataDashboardFishGroup.Ocean
    val statuses = listOfNotNull(state.catalogStatus.takeIf { needsBase }, state.supplementaryStatus.takeIf { needsExtra })
    if (statuses.any { it == PersonalDataDashboardLoadStatus.Loading || it == PersonalDataDashboardLoadStatus.Idle }) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.pdd_catalog_loading))
    }
    if (statuses.any { it in listOf(PersonalDataDashboardLoadStatus.Failed, PersonalDataDashboardLoadStatus.Unavailable) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pdd_catalog_unavailable), Modifier.testTag("dashboard-catalog-error"))
            OutlinedButton(retry, Modifier.testTag("dashboard-catalog-retry")) { Text(stringResource(R.string.pdr_retry)) }
        }
    }
}

internal fun PersonalDataDashboardSectionKind.dashboardLabel() = when (this) {
    PersonalDataDashboardSectionKind.FishingSummary, PersonalDataDashboardSectionKind.GlamourSummary,
    PersonalDataDashboardSectionKind.SavageSummary -> R.string.pdd_overview
    PersonalDataDashboardSectionKind.FishRanking -> R.string.pdr_fish
    PersonalDataDashboardSectionKind.BaitRanking -> R.string.pdr_baits
    PersonalDataDashboardSectionKind.BigFish -> R.string.personal_data_section_big_fish
    PersonalDataDashboardSectionKind.FishingAchievements -> R.string.personal_data_section_achievements
    PersonalDataDashboardSectionKind.OceanFishing -> R.string.pdd_ocean_routes
    PersonalDataDashboardSectionKind.Races -> R.string.personal_data_section_races
    PersonalDataDashboardSectionKind.Stains -> R.string.personal_data_section_colors
    PersonalDataDashboardSectionKind.Accessories -> R.string.personal_data_section_ornaments
    PersonalDataDashboardSectionKind.Vanity -> R.string.personal_data_section_vanity
    PersonalDataDashboardSectionKind.Sets -> R.string.pdr_sets
    PersonalDataDashboardSectionKind.SavageRaids -> R.string.personal_data_section_raids
}

@Composable
internal fun dashboardCategoryLabel(category: String) = when (category) {
    "" -> stringResource(R.string.pdr_unclassified)
    "普通钓场" -> stringResource(R.string.pdr_normal)
    "出海垂钓" -> stringResource(R.string.pdr_ocean)
    "云冠群岛" -> stringResource(R.string.pdr_diadem)
    else -> category
}
