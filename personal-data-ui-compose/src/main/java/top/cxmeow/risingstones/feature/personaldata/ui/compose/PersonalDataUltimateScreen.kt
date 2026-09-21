package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateSection as Section

internal val LocalPersonalDataUltimatePages = staticCompositionLocalOf<SaveableStateHolder?> { null }

/** Native optional Ultimate reader. Its host owns the app bar and opens/closes the model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesPersonalDataUltimatePane(
    viewModel: PersonalDataUltimateViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    coverUrl: (Int) -> String? = { null },
    jobIconUrl: (String) -> String? = { null },
    medalImageUrl: (Int) -> String? = { null },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.isOpen) return
    if (!viewModel.hasCommunityIdentity || state.overviewStatus == PersonalDataUltimateLoadStatus.AuthRequired) {
        Text(stringResource(R.string.pdr_auth), modifier.padding(24.dp).testTag("ultimate-auth"))
        return
    }
    val pages = LocalPersonalDataUltimatePages.current ?: rememberSaveableStateHolder()
    val territory = state.selectedTerritoryType
    BackHandler(enabled = territory != null, onBack = viewModel::clearEncounterSelection)
    pages.SaveableStateProvider(territory?.let { "encounter-$it" } ?: "overview") {
        var summarySelected by rememberSaveable { mutableStateOf(true) }
        val sectionPages = rememberSaveableStateHolder()
        val selected = if (summarySelected) null else state.selectedSection
        sectionPages.SaveableStateProvider(selected?.name ?: "summary") {
            val scroll = rememberLazyListState()
            val restoredPosition = remember(scroll) { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
            // The first restored layout can precede system insets and clamp a list at its old bottom.
            // Reapply the saved anchor after initial layout, without resetting on ordinary resizing.
            LaunchedEffect(scroll) {
                if (restoredPosition.first != 0 || restoredPosition.second != 0) {
                    snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it > 0 }
                    withFrameNanos { }
                    withFrameNanos { }
                    if (!scroll.isScrollInProgress) scroll.requestScrollToItem(restoredPosition.first, restoredPosition.second)
                }
            }
            var selectedPointIndex by rememberSaveable { mutableStateOf<Int?>(null) }
            var selectedPointX by rememberSaveable { mutableStateOf<Double?>(null) }
            var selectedPointY by rememberSaveable { mutableStateOf<Double?>(null) }
            LazyColumn(modifier.fillMaxSize().testTag("ultimate-list"), state = scroll,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (territory != null) TextButton(viewModel::clearEncounterSelection, Modifier.testTag("ultimate-back-to-overview")) {
                        Text(stringResource(R.string.personal_data_back_to_ultimate))
                    } else if (showBack) TextButton(onNavigateBack, Modifier.testTag("ultimate-back")) {
                        Text(stringResource(R.string.personal_data_back_to_boards))
                    }
                    Text(if (territory == null) stringResource(R.string.personal_data_board_ultimate) else ultimateTitle(territory),
                        style = MaterialTheme.typography.headlineSmall)
                }
                item { UltimateLoadState(state.overviewStatus, state.overviewFailure, "overview", viewModel::retryOverview) }
                if (territory == null) {
                    state.records?.let { rows ->
                        item {
                            val distinct = PersonalDataUltimateViews.records(rows)
                            DashboardMetrics(listOf(R.string.personal_data_metric_ultimate_cleared to
                                dashboardNumber(if (distinct.any { it.clearCount == null }) null else distinct.count { (it.clearCount ?: 0) > 0 }.toLong()),
                                R.string.personal_data_metric_ultimate_clears to dashboardNumber(state.totalClears)))
                        }
                        val known = listOf(733, 777, 887, 968, 1122, 1238, 1363)
                        val ids = (known + rows.map { it.territoryType }).distinct()
                        itemsIndexed(ids, key = { _, id -> id }) { _, id ->
                            val record = rows.firstOrNull { it.territoryType == id }
                            Card(Modifier.fillMaxWidth().testTag("ultimate-encounter-$id")
                                .then(if (record != null) Modifier.clickable { viewModel.selectEncounter(id) } else Modifier)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    coverUrl(id)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Crop) }
                                    Text(ultimateTitle(id), style = MaterialTheme.typography.titleMedium)
                                    Text(if (record == null) stringResource(R.string.personal_data_not_cleared)
                                        else stringResource(R.string.pdu_clear_count, dashboardNumber(record.clearCount)))
                                    if (record != null) Text(stringResource(R.string.pdu_open_details))
                                }
                            }
                        }
                    }
                } else {
                    item {
                        PrimaryScrollableTabRow(if (summarySelected) 0 else state.availableSections.indexOf(state.selectedSection) + 1, edgePadding = 0.dp) {
                            Tab(summarySelected, { summarySelected = true }, text = { Text(stringResource(R.string.pdd_overview)) },
                                modifier = Modifier.testTag("ultimate-section-Summary"))
                            state.availableSections.forEach { kind -> Tab(!summarySelected && kind == state.selectedSection,
                                { summarySelected = false; viewModel.selectSection(kind) }, text = { Text(stringResource(kind.ultimateLabel())) },
                                modifier = Modifier.testTag("ultimate-section-$kind")) }
                        }
                    }
                    if (summarySelected) {
                        state.selectedRecord?.let { record -> item { UltimateSummary(record, state.zone, coverUrl, jobIconUrl, medalImageUrl) } }
                        if (territory == 733) item { Text(stringResource(R.string.pdu_phases_unavailable), Modifier.testTag("ultimate-phases-unavailable")) }
                    } else {
                        item { UltimateLoadState(state.currentSection.status, state.currentSection.failure, state.selectedSection.name) {
                            viewModel.retrySection(state.selectedSection) } }
                        if (state.currentSection.data != null) when (state.selectedSection) {
                            Section.Party -> {
                                if (state.party.isEmpty()) item { UltimateEmpty() }
                                itemsIndexed(state.party) { index, member -> UltimateRecord(member.characterName, member.jobName?.let(jobIconUrl), "ultimate-party-$index") {
                                    Text(member.jobName ?: stringResource(R.string.pdr_unknown))
                                    Text(ultimateLocation(member.areaName, member.groupName))
                                } }
                            }
                            Section.Jobs -> {
                                if (state.jobs.isEmpty()) item { UltimateEmpty() }
                                itemsIndexed(state.jobs) { index, job -> UltimateRecord(job.jobName, jobIconUrl(job.jobName), "ultimate-job-$index") {
                                    Text(stringResource(R.string.pdu_clear_count, dashboardNumber(job.times)))
                                } }
                            }
                            Section.Partners -> {
                                if (state.partners.isEmpty()) item { Text(stringResource(R.string.pdu_no_partners), Modifier.testTag("ultimate-empty")) }
                                itemsIndexed(state.visiblePartners) { index, partner -> UltimateRecord(partner.characterName, null, "ultimate-partner-$index") {
                                    Text(ultimateLocation(partner.areaName, partner.groupName))
                                    Text(stringResource(R.string.pdu_joint_entries, dashboardNumber(partner.jointEntries)))
                                } }
                                if (state.hasMorePartners) item { OutlinedButton(viewModel::showMorePartners,
                                    Modifier.fillMaxWidth().testTag("ultimate-partners-more")) { Text(stringResource(R.string.pdr_show_more)) } }
                            }
                            Section.Phases -> {
                                if (state.phases.isEmpty()) item { UltimateEmpty() }
                                itemsIndexed(state.phases) { index, phase -> UltimateRecord(ultimatePhase(phase.phase), null, "ultimate-phase-$index") {
                                    Text(ultimateDate(phase.reachedAt, state.zone))
                                } }
                            }
                            Section.Deaths -> state.deathPlot?.let { plot ->
                                item { Text(stringResource(R.string.pdu_death_note)) }
                                if (plot.points.isEmpty() && plot.excludedCount == 0) item { UltimateEmpty() }
                                if (plot.excludedCount > 0) item { Text(stringResource(R.string.pdu_excluded_points, plot.excludedCount), Modifier.testTag("ultimate-excluded")) }
                                if (plot.points.isNotEmpty()) item {
                                    UltimateScatter(plot, selectedPointIndex, selectedPointX, selectedPointY) { point ->
                                        selectedPointIndex = point?.sourceIndex
                                        selectedPointX = point?.x
                                        selectedPointY = point?.y
                                    }
                                }
                                itemsIndexed(state.visibleDeaths) { _, point -> UltimateRecord(stringResource(R.string.pdu_death_number, point.sourceIndex + 1), null,
                                    "ultimate-death-${point.sourceIndex}") {
                                    Text(stringResource(R.string.pdu_plot_coordinates, ultimateCoordinate(point.x), ultimateCoordinate(point.y)))
                                    Text(stringResource(R.string.pdu_raw_coordinates, ultimateCoordinate(point.record.x), ultimateCoordinate(point.record.y)))
                                } }
                                if (state.hasMoreDeaths) item { OutlinedButton(viewModel::showMoreDeaths,
                                    Modifier.fillMaxWidth().testTag("ultimate-deaths-more")) { Text(stringResource(R.string.pdr_show_more)) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UltimateSummary(record: PersonalDataUltimateRecord, zone: ZoneId, cover: (Int) -> String?, jobIcon: (String) -> String?, medal: (Int) -> String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("ultimate-summary")) {
        cover(record.territoryType)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop) }
        DashboardMetrics(listOf(R.string.personal_data_metric_clears to dashboardNumber(record.clearCount),
            R.string.personal_data_metric_entries_before_clear to dashboardNumber(record.entriesBeforeFirstClear),
            R.string.personal_data_metric_deaths_before_clear to dashboardNumber(record.deathsBeforeFirstClear),
            R.string.pdu_progression_duration to ultimateDuration(record.firstClearDurationSeconds)))
        UltimateRecord(stringResource(R.string.personal_data_first_clear), record.firstClearJob?.let(jobIcon), "ultimate-first-clear") {
            Text(record.firstClearJob ?: stringResource(R.string.pdr_unknown))
            Text(ultimateDate(record.firstClearAt, zone))
        }
        ultimateAchievementLabel(record.territoryType)?.let { label ->
            UltimateRecord(stringResource(label), medal(record.territoryType), "ultimate-achievement") {
                Text(ultimateDate(record.firstClearAt, zone))
            }
        }
    }
}

@Composable
private fun UltimateLoadState(status: PersonalDataUltimateLoadStatus, failure: PersonalDataUltimateFailure?, tag: String, retry: () -> Unit) {
    if (status == PersonalDataUltimateLoadStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ultimate-loading-$tag"))
    if (failure != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (failure == PersonalDataUltimateFailure.Unavailable) R.string.personal_data_unavailable else R.string.pdr_failure),
                Modifier.testTag("ultimate-error-$tag"))
            OutlinedButton(retry, Modifier.testTag("ultimate-retry-$tag")) { Text(stringResource(R.string.pdr_retry)) }
        }
    }
}

@Composable
private fun UltimateRecord(title: String, icon: String?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon?.let { AsyncImage(it, null, Modifier.size(48.dp)) }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun UltimateScatter(plot: UltimateDeathPlot, selectedIndex: Int?, selectedX: Double?, selectedY: Double?,
    onSelected: (UltimatePlotPoint?) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val labels = (0..10).map { i -> measurer.measure(ultimateCoordinateText(plot.axis.max * ((i - 5) / 5.0), locale), textStyle) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val axes = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.pdu_scatter_description)
    val selected = plot.points.firstOrNull { it.sourceIndex == selectedIndex && it.x == selectedX && it.y == selectedY }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pdu_tap_point))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, 380.dp)
            Canvas(Modifier.size(side).testTag("ultimate-scatter").semantics { contentDescription = description }
                .pointerInput(plot) {
                    detectTapGestures { touch ->
                        val inset = 40.dp.toPx()
                        val span = size.width - inset * 2
                        val hit = plot.points.minByOrNull { point ->
                            val position = Offset(inset + (point.x / plot.axis.max * 0.5 + 0.5).toFloat() * span,
                                inset + (0.5 - point.y / plot.axis.max * 0.5).toFloat() * span)
                            (position - touch).getDistanceSquared()
                        }?.takeIf { point ->
                            val position = Offset(inset + (point.x / plot.axis.max * 0.5 + 0.5).toFloat() * span,
                                inset + (0.5 - point.y / plot.axis.max * 0.5).toFloat() * span)
                            (position - touch).getDistance() <= 28.dp.toPx()
                        }
                        onSelected(hit)
                    }
                }) {
                val inset = 40.dp.toPx()
                val span = size.width - inset * 2
                for (i in 0..10) {
                    val coordinate = inset + i / 10f * span
                    drawLine(grid, Offset(inset, coordinate), Offset(inset + span, coordinate))
                    drawLine(grid, Offset(coordinate, inset), Offset(coordinate, inset + span))
                    if (i % 2 == 0) {
                        drawText(labels[i], topLeft = Offset(coordinate - labels[i].size.width / 2f, inset + span + 8.dp.toPx()))
                        drawText(labels[10 - i], topLeft = Offset((inset - labels[10 - i].size.width - 6.dp.toPx()).coerceAtLeast(0f), coordinate - labels[10 - i].size.height / 2f))
                    }
                }
                drawLine(axes, Offset(size.width / 2, inset), Offset(size.width / 2, inset + span), 1.5.dp.toPx())
                drawLine(axes, Offset(inset, size.height / 2), Offset(inset + span, size.height / 2), 1.5.dp.toPx())
                plot.points.forEach { point ->
                    val center = Offset(inset + (point.x / plot.axis.max * 0.5 + 0.5).toFloat() * span,
                        inset + (0.5 - point.y / plot.axis.max * 0.5).toFloat() * span)
                    drawCircle(primary.copy(alpha = 0.6f), 3.dp.toPx(), center)
                    if (point == selected) drawCircle(primary, 7.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                }
            }
        }
        selected?.let { Text(stringResource(R.string.pdu_plot_coordinates, ultimateCoordinate(it.x), ultimateCoordinate(it.y)), Modifier.testTag("ultimate-selected-point")) }
    }
}

@Composable private fun UltimateEmpty() = Text(stringResource(R.string.pdr_empty), Modifier.testTag("ultimate-empty"))
@Composable private fun ultimateLocation(area: String?, group: String?) = listOfNotNull(area, group).filter(String::isNotBlank).joinToString(" · ").ifBlank { stringResource(R.string.pdr_unknown) }
@Composable private fun ultimatePhase(code: String) = if (code == "finish") stringResource(R.string.pdu_phase_complete) else if (code.matches(Regex("p[0-9]+"))) code.uppercase() else code
@Composable private fun ultimateCoordinate(value: Double?) = value?.takeIf(Double::isFinite)?.let { ultimateCoordinateText(it, LocalConfiguration.current.locales[0]) } ?: stringResource(R.string.pdr_unknown)
private fun ultimateCoordinateText(value: Double, locale: java.util.Locale) = String.format(locale, if (kotlin.math.abs(value) >= 100_000) "%.1e" else "%.1f", if (value == 0.0) 0.0 else value)
@Composable private fun ultimateDate(value: UltimateRecordTime?, zone: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    return ultimateDateText(value, zone, locale) ?: stringResource(R.string.pdr_unknown)
}
@Composable private fun ultimateDuration(seconds: Long?): String = seconds?.takeIf { it >= 0 }?.let {
    if (it >= 3600) stringResource(R.string.pdu_hours_minutes_seconds, it / 3600, it % 3600 / 60, it % 60)
    else stringResource(R.string.pdu_minutes_seconds, it / 60, it % 60)
} ?: stringResource(R.string.pdr_unknown)
@Composable private fun ultimateTitle(id: Int) = when (id) {
    733 -> stringResource(R.string.pdu_title_733); 777 -> stringResource(R.string.pdu_title_777)
    887 -> stringResource(R.string.pdu_title_887); 968 -> stringResource(R.string.pdu_title_968)
    1122 -> stringResource(R.string.pdu_title_1122); 1238 -> stringResource(R.string.pdu_title_1238)
    1363 -> stringResource(R.string.pdu_title_1363)
    else -> stringResource(R.string.pdd_raid_number, id)
}
private fun ultimateAchievementLabel(id: Int) = when (id) {
    733 -> R.string.pdu_achievement_733; 777 -> R.string.pdu_achievement_777; 887 -> R.string.pdu_achievement_887
    968 -> R.string.pdu_achievement_968; 1122 -> R.string.pdu_achievement_1122; 1238 -> R.string.pdu_achievement_1238
    1363 -> R.string.pdu_achievement_1363; else -> null
}
private fun Section.ultimateLabel() = when (this) {
    Section.Party -> R.string.personal_data_teammates; Section.Jobs -> R.string.personal_data_jobs
    Section.Partners -> R.string.personal_data_partners; Section.Phases -> R.string.personal_data_phases; Section.Deaths -> R.string.personal_data_deaths
}
