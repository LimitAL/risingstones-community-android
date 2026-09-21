package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineSection as Section

internal val LocalPersonalDataFrontlinePages = staticCompositionLocalOf<SaveableStateHolder?> { null }

/** Optional content pane. The host supplies the app bar and owns explicit open/close navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesPersonalDataFrontlinePane(
    viewModel: PersonalDataFrontlineViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    jobIconUrl: (String, Boolean) -> String? = { _, _ -> null },
    companyFlagUrl: (String) -> String? = { null },
    achievementImageUrl: () -> String? = { null },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.isOpen) return
    if (!viewModel.hasCommunityIdentity || state.currentSection.status == PersonalDataFrontlineLoadStatus.AuthRequired) {
        Text(stringResource(R.string.pdr_auth), modifier.padding(24.dp).testTag("frontline-auth"))
        return
    }
    val pages = LocalPersonalDataFrontlinePages.current ?: rememberSaveableStateHolder()
    val section = state.selectedSection.let { if (it == Section.MapJobs) Section.Maps else it }
    pages.SaveableStateProvider(section.name) {
        val scroll = rememberLazyListState()
        val filters = when (section) {
            Section.Overview -> listOf(state.overallPeriod)
            Section.Weekly -> listOf(state.weeklyMetric)
            Section.Jobs -> listOf(state.jobPeriod, state.selectedJob)
            Section.Best -> listOf(state.bestKind)
            Section.Maps, Section.MapJobs -> listOf(state.selectedMap, state.selectedMapJob)
            Section.Achievements -> listOf(state.includeUnobtained, state.query)
        }.toString()
        var previousFilters by rememberSaveable { mutableStateOf(filters) }
        LaunchedEffect(filters) {
            if (previousFilters != filters) { scroll.scrollToItem(0); previousFilters = filters }
        }
        LazyColumn(modifier.fillMaxSize().testTag("frontline-list"), state = scroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (showBack) TextButton(onNavigateBack, Modifier.testTag("frontline-back")) { Text(stringResource(R.string.personal_data_back_to_boards)) }
                Text(stringResource(R.string.personal_data_board_frontline), style = MaterialTheme.typography.headlineSmall)
            }
            item {
                val sections = Section.entries.filter { it != Section.MapJobs }
                PrimaryScrollableTabRow(sections.indexOf(section), edgePadding = 0.dp) {
                    sections.forEach { kind -> Tab(kind == section, { viewModel.selectSection(kind) },
                        text = { Text(stringResource(kind.frontlineLabel())) }, modifier = Modifier.testTag("frontline-section-$kind")) }
                }
            }
            if (section != Section.Maps) item { FrontlineLoadState(state.currentSection, section, viewModel::retrySection) }
            when (section) {
                Section.Overview -> {
                    if (state.currentSection.data != null) item { FrontlineOverview(state, viewModel::selectOverallPeriod, companyFlagUrl) }
                }
                Section.Weekly -> {
                    item { FrontlineWeeklyFilter(state, viewModel::selectWeeklyMetric) }
                    state.weekly?.let { week ->
                        item { FrontlineWeeklySummary(week) }
                        item { FrontlineWeeklyChart(week, state.weeklyMetric) }
                        itemsIndexed(week.unknownDateRows) { index, row ->
                            FrontlineRecord(stringResource(R.string.pfl_unknown_day), null, "frontline-unknown-day-$index") {
                                Text(stringResource(R.string.pfl_unknown_day_note))
                                DashboardMetrics(listOf(R.string.personal_data_metric_battles to dashboardNumber(row.battles),
                                    R.string.personal_data_metric_wins to dashboardNumber(row.wins), R.string.personal_data_metric_kills to dashboardNumber(row.kills),
                                    R.string.personal_data_metric_deaths to dashboardNumber(row.deaths), R.string.personal_data_metric_assists to dashboardNumber(row.assists)))
                            }
                        }
                    }
                }
                Section.Jobs -> if (state.currentSection.data != null) item { FrontlineJobs(state, viewModel, jobIconUrl) }
                Section.Best -> {
                    item { FrontlineBestFilter(state, viewModel::selectBestKind) }
                    if (state.currentSection.data != null) item { state.currentBest?.let { FrontlineBest(it, jobIconUrl, state.zone) } ?: FrontlineEmpty() }
                }
                Section.Maps, Section.MapJobs -> item { FrontlineMaps(state, viewModel, jobIconUrl) }
                Section.Achievements -> {
                    item { FrontlineCatalogState(state, viewModel::retryCatalogs, requireAchievements = true) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(state.query, viewModel::updateQuery, Modifier.fillMaxWidth().testTag("frontline-search"),
                                label = { Text(stringResource(R.string.pdr_search)) }, singleLine = true)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!state.includeUnobtained, { viewModel.setIncludeUnobtained(false) },
                                    { Text(stringResource(R.string.pdd_with_records)) }, Modifier.testTag("frontline-recorded"))
                                FilterChip(state.includeUnobtained, { viewModel.setIncludeUnobtained(true) },
                                    { Text(stringResource(R.string.pdd_include_unobtained)) }, Modifier.testTag("frontline-all-achievements"))
                            }
                        }
                    }
                    if (state.currentSection.data != null && state.totalAchievements == 0) item { FrontlineEmpty() }
                    itemsIndexed(state.visibleAchievements) { index, row ->
                        val record = row.record
                        FrontlineRecord(record?.name ?: row.catalog?.name ?: stringResource(R.string.pdd_achievement_number, row.achievementId),
                            achievementImageUrl(), "frontline-achievement-$index") {
                            (record?.detail ?: row.catalog?.detail)?.let { Text(it) }
                            Text(if (record == null) stringResource(R.string.pdr_not_collected)
                                else stringResource(R.string.pdr_recorded_on, frontlineDate(record.obtainedAt, state.zone)))
                        }
                    }
                    if (state.totalAchievements > 0) item {
                        Text(stringResource(R.string.pdr_shown, state.visibleAchievements.size, state.totalAchievements), Modifier.testTag("frontline-shown"))
                        if (state.hasMoreAchievements) OutlinedButton(viewModel::showMore, Modifier.fillMaxWidth().testTag("frontline-more")) { Text(stringResource(R.string.pdr_show_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrontlineLoadState(state: PersonalDataFrontlineSectionState, section: Section, retry: (Section) -> Unit) {
    if (state.status == PersonalDataFrontlineLoadStatus.Loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("frontline-loading-$section"))
        Text(stringResource(R.string.pdr_loading))
    }
    if (state.failure != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (state.failure == PersonalDataFrontlineFailure.Unavailable) R.string.personal_data_unavailable else R.string.pdr_failure),
                Modifier.testTag("frontline-error-$section"))
            OutlinedButton({ retry(section) }, Modifier.testTag("frontline-retry-$section")) { Text(stringResource(R.string.pdr_retry)) }
        }
    }
}

@Composable
private fun FrontlineCatalogState(state: PersonalDataFrontlineUiState, retry: () -> Unit, requireAchievements: Boolean = false) {
    if (state.catalogStatus == PersonalDataFrontlineLoadStatus.Loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.pdd_catalog_loading))
    }
    if (state.catalogStatus in listOf(PersonalDataFrontlineLoadStatus.Failed, PersonalDataFrontlineLoadStatus.Unavailable) ||
        (requireAchievements && state.catalogStatus == PersonalDataFrontlineLoadStatus.Loaded && !state.hasAchievementCatalog)) {
        Text(stringResource(R.string.pdd_catalog_unavailable), Modifier.testTag("frontline-catalog-error"))
        OutlinedButton(retry, Modifier.testTag("frontline-catalog-retry")) { Text(stringResource(R.string.pdr_retry)) }
    }
}

@Composable
private fun FrontlineOverview(state: PersonalDataFrontlineUiState, period: (FrontlinePeriodKind) -> Unit, flag: (String) -> String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pfl_all_time_summary), style = MaterialTheme.typography.titleMedium)
        val total = state.allTimeOverview
        DashboardMetrics(listOf(R.string.personal_data_metric_battles to dashboardNumber(total?.battles),
            R.string.personal_data_metric_kda to frontlineDecimal(total?.kda), R.string.personal_data_metric_kills to dashboardNumber(total?.kills),
            R.string.personal_data_metric_win_rate to dashboardPercent(total?.winRate)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            total?.companyName?.let(flag)?.let { AsyncImage(it, null, Modifier.size(56.dp)) }
            DashboardValue(R.string.personal_data_metric_grand_company, total?.companyName ?: stringResource(R.string.pdr_unknown))
        }
        DashboardMetrics(listOf(R.string.personal_data_metric_pvp_rank to dashboardNumber(total?.pvpRank),
            R.string.personal_data_metric_series_level to dashboardNumber(total?.seriesLevel)))
        FrontlinePeriods(state.overallPeriod, "overview", period)
        val overview = state.overall
        if (overview == null) FrontlineEmpty() else {
            DashboardMetrics(listOf(R.string.personal_data_metric_elapsed to (overview.elapsedHours?.let { stringResource(R.string.pdd_hours, it) } ?: stringResource(R.string.pdr_unknown)),
                R.string.pfl_objectives to dashboardNumber(overview.occupiedObjectives),
                R.string.personal_data_metric_kda to frontlineDecimal(overview.kda), R.string.personal_data_metric_win_rate to dashboardPercent(overview.winRate)))
            FrontlineAveragesPanel(overview.averages, includeDamage = false)
            FrontlineRadar(overview.ranks)
        }
        val unknown = (state.currentSection.data as? PersonalDataFrontlineData.Overview)?.rows.orEmpty().count { it.period == null }
        if (unknown > 0) Text(stringResource(R.string.pfl_unknown_period_records, unknown))
    }
}

@Composable
private fun FrontlinePeriods(selected: FrontlinePeriodKind, tag: String, choose: (FrontlinePeriodKind) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrontlinePeriodKind.entries.forEach { period -> FilterChip(period == selected, { choose(period) },
            { Text(stringResource(period.frontlineLabel())) }, Modifier.testTag("frontline-$tag-period-$period")) }
    }
}

@Composable
private fun FrontlineWeeklyFilter(state: PersonalDataFrontlineUiState, choose: (PersonalDataFrontlineWeeklyMetric) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pfl_last_seven_note))
        FrontlineChoice(stringResource(R.string.pfl_metric), state.weeklyMetric, PersonalDataFrontlineWeeklyMetric.entries,
            { stringResource(it.frontlineLabel()) }, "frontline-weekly-metric", choose)
    }
}

@Composable
private fun FrontlineWeeklySummary(week: FrontlineWeekView) {
    val total = week.totals
    Text(stringResource(R.string.pfl_date_range, week.days.first().date.toString(), week.days.last().date.toString()))
    DashboardMetrics(listOf(R.string.personal_data_metric_battles to dashboardNumber(total.battles),
        R.string.personal_data_metric_wins to dashboardNumber(total.wins), R.string.personal_data_metric_kills to dashboardNumber(total.kills),
        R.string.personal_data_metric_deaths to dashboardNumber(total.deaths), R.string.personal_data_metric_win_rate to dashboardPercent(total.winRate),
        R.string.personal_data_metric_kda to frontlineDecimal(total.kda)))
}

@Composable
private fun FrontlineWeeklyChart(week: FrontlineWeekView, metric: PersonalDataFrontlineWeeklyMetric) {
    val max = week.days.mapNotNull { it.value(metric) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val color = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.surfaceVariant
    val dateFormat = DateTimeFormatter.ofPattern("M/d", LocalConfiguration.current.locales[0])
    Card(Modifier.fillMaxWidth().testTag("frontline-weekly-chart")) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            week.days.forEach { day ->
                Column(Modifier.width(68.dp).testTag("frontline-day-${day.date}"), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(frontlineDayValue(day, metric), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.height(100.dp).width(24.dp).background(baseline), contentAlignment = Alignment.BottomCenter) {
                        day.value(metric)?.let { Box(Modifier.fillMaxWidth().fillMaxHeight((it / max).toFloat().coerceIn(0f, 1f)).background(color)) }
                    }
                    Text(day.date.format(dateFormat), style = MaterialTheme.typography.labelMedium)
                    if (day.isMissing) Text(stringResource(R.string.pfl_missing_day), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun FrontlineJobs(state: PersonalDataFrontlineUiState, model: PersonalDataFrontlineViewModel, icon: (String, Boolean) -> String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FrontlinePeriods(state.jobPeriod, "jobs", model::selectJobPeriod)
        FrontlineChoice(stringResource(R.string.personal_data_job), state.selectedJob, state.availableJobs.map { it.jobName },
            { it ?: stringResource(R.string.pdr_unknown) }, "frontline-job", { it?.let(model::selectJob) })
        val job = state.currentJob
        if (job == null) FrontlineEmpty() else {
            FrontlineRecord(job.jobName, icon(job.jobName, true), "frontline-job-details") {
                DashboardMetrics(listOf(R.string.personal_data_metric_battles to dashboardNumber(job.battles),
                    R.string.pfl_use_rate to dashboardPercent(job.useRate), R.string.personal_data_metric_kills to dashboardNumber(job.kills),
                    R.string.personal_data_metric_win_rate to dashboardPercent(job.winRate), R.string.personal_data_metric_kda to frontlineDecimal(job.kda),
                    R.string.pfl_limit_breaks to dashboardNumber(job.limitBreaks)))
                Text(stringResource(R.string.pfl_kda_percentile, dashboardPercent(job.kdaPercentile)))
                FrontlineAveragesPanel(job.averages, includeDamage = true)
            }
        }
        Text(stringResource(R.string.pfl_job_distribution), style = MaterialTheme.typography.titleSmall)
        state.availableJobs.forEachIndexed { index, row ->
            Column(Modifier.fillMaxWidth().testTag("frontline-job-usage-$index")) {
                TextButton({ model.selectJob(row.jobName) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pfl_selection, row.jobName, dashboardPercent(row.useRate)))
                }
                row.useRate?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { rate ->
                    LinearProgressIndicator(progress = { rate.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val unknown = (state.currentSection.data as? PersonalDataFrontlineData.Jobs)?.rows.orEmpty().count { it.period == null }
        if (unknown > 0) Text(stringResource(R.string.pfl_unknown_period_records, unknown))
    }
}

@Composable
private fun FrontlineAveragesPanel(averages: FrontlineAverages, includeDamage: Boolean) {
    Text(stringResource(R.string.pfl_averages), style = MaterialTheme.typography.titleSmall)
    DashboardMetrics(buildList {
        add(R.string.personal_data_metric_kills to frontlineDecimal(averages.kills))
        add(R.string.personal_data_metric_assists to frontlineDecimal(averages.assists))
        add(R.string.personal_data_metric_deaths to frontlineDecimal(averages.deaths))
        if (includeDamage) {
            add(R.string.pfl_damage to frontlineDecimal(averages.damage))
            add(R.string.pfl_healing to frontlineDecimal(averages.healing))
            add(R.string.pfl_damage_taken to frontlineDecimal(averages.damageTaken))
        }
    })
}

@Composable
private fun FrontlineBestFilter(state: PersonalDataFrontlineUiState, choose: (FrontlineBestKind) -> Unit) {
    val rows = (state.currentSection.data as? PersonalDataFrontlineData.Best)?.rows.orEmpty()
    FrontlineChoice(stringResource(R.string.personal_data_section_best), state.bestKind,
        FrontlineBestKind.entries.filter { it != FrontlineBestKind.Unknown || rows.any { row -> row.kind == it } },
        { stringResource(it.frontlineLabel()) }, "frontline-best-kind", choose)
}

@Composable
private fun FrontlineBest(record: FrontlineBestRecord, icon: (String, Boolean) -> String?, zone: java.time.ZoneId) {
    FrontlineRecord(record.mapName ?: stringResource(R.string.pdr_unknown), record.jobName?.let { icon(it, false) }, "frontline-best-details") {
        Text(record.jobName ?: stringResource(R.string.pdr_unknown))
        Text(frontlineDate(record.recordedAt, zone))
        Text(stringResource(when (record.placement) {
            FrontlinePlacement.First -> R.string.pfl_first
            FrontlinePlacement.Second -> R.string.pfl_second
            FrontlinePlacement.Third -> R.string.pfl_third
            null -> R.string.pdr_unknown
        }), Modifier.testTag("frontline-best-placement"))
        DashboardMetrics(listOf(R.string.personal_data_metric_kills to dashboardNumber(record.kills), R.string.personal_data_metric_deaths to dashboardNumber(record.deaths),
            R.string.personal_data_metric_assists to dashboardNumber(record.assists), R.string.pfl_damage to dashboardNumber(record.damage),
            R.string.pfl_damage_taken to dashboardNumber(record.damageTaken), R.string.pfl_healing to dashboardNumber(record.healing)))
        Text(stringResource(R.string.pfl_scores), style = MaterialTheme.typography.titleSmall)
        PersonalDataFrontlineViews.scores(record).forEach { score ->
            Column(Modifier.testTag("frontline-team-${score.teamNumber}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.pfl_team_score, score.teamNumber, dashboardNumber(score.points)))
                score.fraction?.let { fraction -> LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun FrontlineMaps(state: PersonalDataFrontlineUiState, model: PersonalDataFrontlineViewModel, icon: (String, Boolean) -> String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FrontlineCatalogState(state, model::retryCatalogs)
        FrontlineLoadState(state.sections[Section.Maps] ?: PersonalDataFrontlineSectionState(), Section.Maps, model::retrySection)
        FrontlineChoice(stringResource(R.string.pfl_map), state.selectedMap, state.availableMaps,
            { it ?: stringResource(R.string.pdr_unknown) }, "frontline-map", { it?.let(model::selectMap) })
        FrontlineLoadState(state.sections[Section.MapJobs] ?: PersonalDataFrontlineSectionState(), Section.MapJobs, model::retrySection)
        FrontlineChoice(stringResource(R.string.personal_data_job), state.selectedMapJob, listOf(null) + state.availableMapJobs.map { it.jobName },
            { it ?: stringResource(R.string.pdr_all) }, "frontline-map-job", model::selectMapJob)
        val data = state.currentMapStats
        if (data == null) {
            if (state.sections[if (state.selectedMapJob == null) Section.Maps else Section.MapJobs]?.data != null) FrontlineEmpty()
        } else FrontlineRecord(data.mapName, state.selectedMapJob?.let { icon(it, true) }, "frontline-map-details") {
            Text(state.selectedMapJob ?: stringResource(R.string.pdr_all))
            DashboardMetrics(listOf(R.string.personal_data_metric_battles to dashboardNumber(data.battles),
                R.string.personal_data_metric_wins to dashboardNumber(data.wins), R.string.personal_data_metric_kills to dashboardNumber(data.kills),
                R.string.personal_data_metric_win_rate to dashboardPercent(data.winRate)))
        }
    }
}

@Composable
private fun FrontlineRadar(ranks: FrontlineRanks) {
    val values = listOf(ranks.kills, ranks.healing, ranks.damageTaken, ranks.damage, ranks.survival, ranks.assists)
    val labels = listOf(R.string.personal_data_metric_kills, R.string.pfl_healing, R.string.pfl_damage_taken,
        R.string.pfl_damage, R.string.pfl_survival, R.string.personal_data_metric_assists)
    val labelText = labels.map { stringResource(it) }
    val description = stringResource(R.string.pfl_rank_chart)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measured = labelText.map { measurer.measure(it, style) }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text(description, style = MaterialTheme.typography.titleMedium)
    Canvas(Modifier.fillMaxWidth().height(240.dp).testTag("frontline-radar").semantics { contentDescription = description }) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxLabelWidth = measured.maxOf { it.size.width }.toFloat()
        val radius = minOf(size.height * 0.30f, ((size.width - maxLabelWidth) / 2f - 4.dp.toPx()) / 1.35f).coerceAtLeast(0f)
        fun point(index: Int, fraction: Float): Offset {
            val angle = -PI / 2 + index * PI / 3
            return center + Offset((cos(angle) * radius * fraction).toFloat(), (sin(angle) * radius * fraction).toFloat())
        }
        for (ring in 1..4) {
            val path = Path().apply { (0..5).forEach { i -> val p = point(i, ring / 4f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
            drawPath(path, grid, style = Stroke(1.dp.toPx()))
        }
        (0..5).forEach { i ->
            drawLine(grid, center, point(i, 1f))
            val p = point(i, 1.35f)
            drawText(measured[i], topLeft = p - Offset(measured[i].size.width / 2f, measured[i].size.height / 2f))
        }
        if (values.all { it != null && it.isFinite() && it in 0.0..100.0 }) {
            val path = Path().apply { values.forEachIndexed { i, value -> val p = point(i, value!!.toFloat() / 100f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
            drawPath(path, color.copy(alpha = 0.18f)); drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
    }
    DashboardMetrics(labels.zip(values).map { (label, score) -> label to when {
        score == null -> stringResource(R.string.pdr_unknown)
        score >= 80 -> stringResource(R.string.pfl_score_grade, frontlineDecimal(score), stringResource(R.string.pfl_grade_s))
        score >= 50 -> stringResource(R.string.pfl_score_grade, frontlineDecimal(score), stringResource(R.string.pfl_grade_a))
        else -> frontlineDecimal(score)
    } })
}

@Composable
private fun <T> FrontlineChoice(label: String, selected: T, options: List<T>, text: @Composable (T) -> String, tag: String, choose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag(tag), enabled = options.isNotEmpty()) {
            Text(stringResource(R.string.pfl_selection, label, text(selected)))
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, option -> DropdownMenuItem(text = { Text(text(option)) },
                onClick = { choose(option); expanded = false }, modifier = Modifier.testTag("$tag-option-$index")) }
        }
    }
}

@Composable private fun FrontlineEmpty() = Text(stringResource(R.string.pdr_empty), Modifier.testTag("frontline-empty"))
@Composable private fun frontlineDecimal(value: Double?): String = value?.takeIf(Double::isFinite)?.let { stringResource(R.string.pfl_decimal, it) } ?: stringResource(R.string.pdr_unknown)
@Composable private fun frontlineDayValue(day: FrontlineDayView, metric: PersonalDataFrontlineWeeklyMetric): String = when (metric) {
    PersonalDataFrontlineWeeklyMetric.Battles -> dashboardNumber(day.battles)
    PersonalDataFrontlineWeeklyMetric.Wins -> dashboardNumber(day.wins)
    PersonalDataFrontlineWeeklyMetric.Kills -> dashboardNumber(day.kills)
    PersonalDataFrontlineWeeklyMetric.Deaths -> dashboardNumber(day.deaths)
    PersonalDataFrontlineWeeklyMetric.WinRate -> day.winRate?.let { stringResource(R.string.pfl_daily_percent, it * 100) } ?: stringResource(R.string.pdr_unknown)
    PersonalDataFrontlineWeeklyMetric.Kda -> frontlineDecimal(day.kda)
}

private fun Section.frontlineLabel() = when (this) {
    Section.Overview -> R.string.pdd_overview
    Section.Weekly -> R.string.pfl_seven_days
    Section.Jobs -> R.string.personal_data_section_jobs
    Section.Best -> R.string.personal_data_section_best
    Section.Maps, Section.MapJobs -> R.string.personal_data_section_maps
    Section.Achievements -> R.string.personal_data_section_achievements
}
private fun FrontlinePeriodKind.frontlineLabel() = when (this) {
    FrontlinePeriodKind.Total -> R.string.personal_data_period_total
    FrontlinePeriodKind.Since51 -> R.string.personal_data_period_since_51
    FrontlinePeriodKind.Last30Days -> R.string.personal_data_period_last_30
}
private fun FrontlineBestKind.frontlineLabel() = when (this) {
    FrontlineBestKind.Kills -> R.string.personal_data_metric_kills
    FrontlineBestKind.Assists -> R.string.personal_data_metric_assists
    FrontlineBestKind.Damage -> R.string.pfl_damage
    FrontlineBestKind.DamageTaken -> R.string.pfl_damage_taken
    FrontlineBestKind.Healing -> R.string.pfl_healing
    FrontlineBestKind.Unknown -> R.string.pdr_unknown
}
private fun PersonalDataFrontlineWeeklyMetric.frontlineLabel() = when (this) {
    PersonalDataFrontlineWeeklyMetric.Battles -> R.string.personal_data_metric_battles
    PersonalDataFrontlineWeeklyMetric.Wins -> R.string.personal_data_metric_wins
    PersonalDataFrontlineWeeklyMetric.Kills -> R.string.personal_data_metric_kills
    PersonalDataFrontlineWeeklyMetric.Deaths -> R.string.personal_data_metric_deaths
    PersonalDataFrontlineWeeklyMetric.WinRate -> R.string.personal_data_metric_win_rate
    PersonalDataFrontlineWeeklyMetric.Kda -> R.string.personal_data_metric_kda
}

@Composable
private fun frontlineDate(value: FrontlineDayStamp?, zone: java.time.ZoneId): String =
    frontlineDateText(value, zone, LocalConfiguration.current.locales[0]) ?: stringResource(R.string.pdr_unknown)

@Composable
private fun FrontlineRecord(title: String, icon: String?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon?.let { AsyncImage(it, null, Modifier.size(48.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            content()
        }
    }
}
