package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

internal val LocalPersonalDataReadingOpen = compositionLocalOf<((PersonalDataReadingPage) -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesPersonalDataReadingScreen(
    viewModel: PersonalDataReadingViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    itemIconUrl: (Int) -> String? = { null },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.activePage ?: return
    val listScroll = rememberLazyListState()
    val itemScroll = rememberSaveable(state.selectedSetKey, saver = LazyListState.Saver) { LazyListState() }
    val criteriaKey = listOf(page.name, state.query, state.category, state.setFilter.name, state.setSort.name).toString()
    var previousCriteria by rememberSaveable { mutableStateOf(criteriaKey) }
    LaunchedEffect(criteriaKey) {
        if (previousCriteria != criteriaKey) {
            listScroll.scrollToItem(0)
            previousCriteria = criteriaKey
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = personalDataLayoutFromPixels(constraints.maxWidth, LocalDensity.current.density) ==
            RisingStonesPersonalDataLayoutMode.Compact
        val showingSet = page == PersonalDataReadingPage.Sets && state.selectedSet != null
        val back: () -> Unit = if (compact && showingSet) viewModel::clearSetSelection else onNavigateBack
        BackHandler(onBack = back)
        Scaffold(
            topBar = { TopAppBar(
                title = { Text(stringResource(page.title())) },
                navigationIcon = { TextButton(back, Modifier.testTag("reading-back")) { Text(stringResource(R.string.pdr_back)) } },
                actions = { TextButton(viewModel::refresh, enabled = viewModel.hasCommunityIdentity &&
                    state.currentPageState.status != PersonalDataReadingLoadStatus.Loading,
                    modifier = Modifier.testTag("reading-refresh")) { Text(stringResource(R.string.pdr_refresh)) } },
            ) },
        ) { padding ->
            val authenticated = viewModel.hasCommunityIdentity &&
                state.currentPageState.status != PersonalDataReadingLoadStatus.AuthRequired
            if (!authenticated || !viewModel.supportsReading) {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (!authenticated) R.string.pdr_auth else R.string.pdr_unsupported),
                        Modifier.testTag("reading-unavailable"))
                }
            } else if (page == PersonalDataReadingPage.Sets && showingSet && compact) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (state.currentPageState.status == PersonalDataReadingLoadStatus.Loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reading-loading"))
                    }
                    state.currentPageState.failure?.let { failure ->
                        ReadingFailure(failure, viewModel::refresh)
                    }
                    if (state.catalogStatus != PersonalDataReadingLoadStatus.Loaded) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            CatalogStatus(state, viewModel::retryCatalogs)
                        }
                    }
                    key(state.selectedSetKey) {
                        SetItems(requireNotNull(state.selectedSet), itemIconUrl, itemScroll,
                            Modifier.fillMaxWidth().weight(1f).testTag("reading-set-details"))
                    }
                }
            } else {
                Row(Modifier.fillMaxSize().padding(padding), horizontalArrangement = Arrangement.Center) {
                    val listModifier = if (page == PersonalDataReadingPage.Sets && !compact) {
                        Modifier.widthIn(max = 420.dp).weight(0.48f).fillMaxHeight()
                    } else Modifier.widthIn(max = 900.dp).fillMaxSize()
                    LazyColumn(listModifier.testTag("reading-list"), state = listScroll,
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { ReadingControls(state, viewModel) }
                        if (page == PersonalDataReadingPage.Sets) item { CatalogStatus(state, viewModel::retryCatalogs) }
                        if (state.currentPageState.status == PersonalDataReadingLoadStatus.Loading) item {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reading-loading"))
                            Text(stringResource(R.string.pdr_loading))
                        }
                        if (state.currentPageState.failure != null) item {
                            ReadingFailure(state.currentPageState.failure, viewModel::refresh)
                        }
                        if (state.currentPageState.hasLoaded) {
                            if (state.totalFiltered == 0) item {
                                val filtered = state.query.isNotBlank() || state.category != null || state.setFilter != PersonalDataSetFilter.All
                                Text(stringResource(if (filtered) R.string.pdr_no_matches else R.string.pdr_empty),
                                    Modifier.testTag("reading-empty"))
                            }
                            when (page) {
                                PersonalDataReadingPage.Fish, PersonalDataReadingPage.Baits ->
                                    itemsIndexed(state.visibleFishingRows) { index, record ->
                                        RankCard(record, page, index)
                                    }
                                PersonalDataReadingPage.Races -> itemsIndexed(state.visibleRaces) { index, record ->
                                    RaceCard(record, index)
                                }
                                PersonalDataReadingPage.Sets -> itemsIndexed(state.visibleSets, key = { _, row -> row.key }) { _, row ->
                                    SetCard(row, row.key == state.selectedSetKey, itemIconUrl) { viewModel.selectSet(row.key) }
                                }
                            }
                            readingFooter(state, viewModel::showMore)
                        }
                    }
                    if (page == PersonalDataReadingPage.Sets && !compact) {
                        VerticalDivider()
                        val row = state.selectedSet
                        if (row == null) Box(Modifier.weight(0.52f).fillMaxHeight().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.pdr_select_set), Modifier.testTag("reading-select-set"))
                        } else key(row.key) {
                            SetItems(row, itemIconUrl, itemScroll, Modifier.weight(0.52f).fillMaxHeight().testTag("reading-set-details"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingControls(state: PersonalDataReadingUiState, model: PersonalDataReadingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = state.query, onValueChange = model::updateQuery, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("reading-search"),
            label = { Text(stringResource(R.string.pdr_search)) },
            trailingIcon = if (state.query.isNotEmpty()) ({
                TextButton({ model.updateQuery("") }, Modifier.testTag("reading-clear-search")) { Text(stringResource(R.string.pdr_clear_search)) }
            }) else null)
        if (state.activePage in listOf(PersonalDataReadingPage.Fish, PersonalDataReadingPage.Baits)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.category == null, { model.selectCategory(null) }, { Text(stringResource(R.string.pdr_all)) },
                    Modifier.testTag("reading-category-all"))
                state.fishingCategories.forEachIndexed { index, category ->
                    FilterChip(state.category == category, { model.selectCategory(category) }, { Text(categoryLabel(category)) },
                        Modifier.testTag("reading-category-$index"))
                }
            }
        }
        if (state.activePage == PersonalDataReadingPage.Sets) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalDataSetFilter.entries.forEach { filter ->
                    FilterChip(state.setFilter == filter, { model.setSetFilter(filter) }, { Text(stringResource(filter.label())) },
                        Modifier.testTag("reading-filter-$filter"))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalDataSetSort.entries.forEach { sort ->
                    FilterChip(state.setSort == sort, { model.setSetSort(sort) }, { Text(stringResource(sort.label())) },
                        Modifier.testTag("reading-sort-$sort"))
                }
            }
        }
    }
}

@Composable
private fun CatalogStatus(state: PersonalDataReadingUiState, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.setsProgress?.let { Text(stringResource(R.string.pdr_sets_progress, it.completed, it.total),
            Modifier.testTag("reading-sets-progress"), style = MaterialTheme.typography.titleSmall) }
        when (state.catalogStatus) {
            PersonalDataReadingLoadStatus.Idle, PersonalDataReadingLoadStatus.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.pdr_catalog_loading))
            }
            PersonalDataReadingLoadStatus.Failed, PersonalDataReadingLoadStatus.Unavailable -> {
                Text(stringResource(R.string.pdr_catalog_unavailable), Modifier.testTag("reading-catalog-unavailable"))
                OutlinedButton(retry, Modifier.testTag("reading-retry-catalogs")) { Text(stringResource(R.string.pdr_retry)) }
            }
            PersonalDataReadingLoadStatus.AuthRequired -> Text(stringResource(R.string.pdr_auth))
            PersonalDataReadingLoadStatus.Loaded -> Unit
        }
    }
}

@Composable
private fun ReadingFailure(failure: PersonalDataReadingFailure?, retry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (failure) {
                PersonalDataReadingFailure.AuthenticationRequired -> R.string.pdr_auth
                PersonalDataReadingFailure.Unavailable -> R.string.pdr_unsupported
                else -> R.string.pdr_failure
            }), Modifier.testTag("reading-error"))
            if (failure == PersonalDataReadingFailure.LoadFailed) {
                OutlinedButton(retry, Modifier.testTag("reading-retry")) { Text(stringResource(R.string.pdr_retry)) }
            }
        }
    }
}

@Composable
private fun RankCard(record: PersonalDataFishingRank, page: PersonalDataReadingPage, index: Int) {
    Card(Modifier.fillMaxWidth().testTag("reading-rank-$index")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.name, style = MaterialTheme.typography.titleMedium)
            record.category?.let { Text(categoryLabel(it), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(if (page == PersonalDataReadingPage.Fish) R.string.pdr_fish_count else R.string.pdr_bait_count, record.count))
        }
    }
}

@Composable
private fun RaceCard(record: PersonalDataRaceUsage, index: Int) {
    Card(Modifier.fillMaxWidth().testTag("reading-race-$index")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${record.race} ${record.gender}", style = MaterialTheme.typography.titleMedium)
            if (record.isCurrent) Text(stringResource(R.string.pdr_current), style = MaterialTheme.typography.labelLarge)
            if (record.isMostUsed) Text(stringResource(R.string.pdr_most_used), style = MaterialTheme.typography.labelLarge)
            val ratio = record.proportion?.takeIf { it.isFinite() && it in 0.0..1.0 }
            Text(if (ratio == null) stringResource(R.string.pdr_unknown) else stringResource(R.string.pdr_proportion, ratio * 100.0))
            if (ratio != null) LinearProgressIndicator(progress = { ratio.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(record.days?.let { stringResource(R.string.pdr_days, it) } ?: stringResource(R.string.pdr_unknown))
        }
    }
}

@Composable
private fun SetCard(row: PersonalDataSetRow, selected: Boolean, iconUrl: (Int) -> String?, onClick: () -> Unit) {
    Card(onClick, Modifier.fillMaxWidth().semantics { this.selected = selected }.testTag("reading-set-${row.key}"), colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.catalog?.iconId?.let(iconUrl)?.let { AsyncImage(it, null, Modifier.size(48.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SetName(row)
                SetStatus(row)
            }
        }
    }
}

@Composable
private fun SetName(row: PersonalDataSetRow) {
    Text(row.catalog?.name ?: stringResource(R.string.pdr_set_number, row.setId),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SetStatus(row: PersonalDataSetRow) {
    Text(stringResource(when (row.completion) {
        PersonalDataSetCompletion.Unrecorded -> R.string.pdr_unrecorded
        PersonalDataSetCompletion.Partial -> R.string.pdr_partial
        PersonalDataSetCompletion.Complete -> R.string.pdr_complete
        PersonalDataSetCompletion.Unknown -> R.string.pdr_status_unknown
    }))
    row.catalog?.takeIf { it.items.isNotEmpty() }?.let {
        Text(stringResource(R.string.pdr_item_progress, row.knownItemIds.size, it.items.map { item -> item.itemId }.distinct().size),
            style = MaterialTheme.typography.bodySmall)
    }
    row.record?.let { record ->
        Text(stringResource(R.string.pdr_recorded_on, record.recordedAt?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(it)
        } ?: stringResource(R.string.pdr_unknown)), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SetItems(row: PersonalDataSetRow, iconUrl: (Int) -> String?, scroll: LazyListState, modifier: Modifier) {
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SetName(row); SetStatus(row) } }
        item { Text(stringResource(R.string.pdr_items), style = MaterialTheme.typography.titleSmall) }
        val items = row.catalog?.items
        if (items == null) {
            item { Text(stringResource(R.string.pdr_catalog_unavailable)) }
            itemsIndexed(row.record?.itemIds.orEmpty().sorted()) { _, id -> Text(stringResource(R.string.pdr_item_number, id)) }
        } else itemsIndexed(items) { _, item ->
            Card(Modifier.fillMaxWidth().testTag("reading-item-${item.itemId}")) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    iconUrl(item.iconId)?.let { AsyncImage(it, null, Modifier.size(48.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(when {
                            item.itemId in row.knownItemIds -> R.string.pdr_collected
                            row.completion == PersonalDataSetCompletion.Unknown -> R.string.pdr_unknown
                            else -> R.string.pdr_not_collected
                        }),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.readingFooter(state: PersonalDataReadingUiState, showMore: () -> Unit) {
    item {
        Text(stringResource(R.string.pdr_shown, minOf(state.visibleLimit, state.totalFiltered), state.totalFiltered),
            Modifier.testTag("reading-shown"), style = MaterialTheme.typography.bodySmall)
        if (state.hasMore) OutlinedButton(showMore, Modifier.fillMaxWidth().testTag("reading-show-more")) { Text(stringResource(R.string.pdr_show_more)) }
    }
}

@Composable
private fun categoryLabel(value: String) = when (value) {
    "" -> stringResource(R.string.pdr_unclassified)
    "普通钓场" -> stringResource(R.string.pdr_normal)
    "出海垂钓" -> stringResource(R.string.pdr_ocean)
    "云冠群岛" -> stringResource(R.string.pdr_diadem)
    else -> value
}

internal fun PersonalDataReadingPage.title() = when (this) {
    PersonalDataReadingPage.Fish -> R.string.pdr_fish
    PersonalDataReadingPage.Baits -> R.string.pdr_baits
    PersonalDataReadingPage.Races -> R.string.pdr_races
    PersonalDataReadingPage.Sets -> R.string.pdr_sets
}
internal fun PersonalDataReadingPage.openLabel() = when (this) {
    PersonalDataReadingPage.Fish -> R.string.pdr_open_fish
    PersonalDataReadingPage.Baits -> R.string.pdr_open_baits
    PersonalDataReadingPage.Races -> R.string.pdr_open_races
    PersonalDataReadingPage.Sets -> R.string.pdr_open_sets
}
private fun PersonalDataSetFilter.label() = when (this) {
    PersonalDataSetFilter.All -> R.string.pdr_all
    PersonalDataSetFilter.Recorded -> R.string.pdr_recorded
    PersonalDataSetFilter.Unrecorded -> R.string.pdr_unrecorded
}
private fun PersonalDataSetSort.label() = when (this) {
    PersonalDataSetSort.OldestFirst -> R.string.pdr_oldest
    PersonalDataSetSort.NewestFirst -> R.string.pdr_newest
}
