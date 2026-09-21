package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

internal fun LazyListScope.dashboardContentItems(
    data: PersonalDataDashboardData,
    state: PersonalDataDashboardUiState,
    more: () -> Unit,
    openReading: (PersonalDataReadingPage) -> Unit,
    itemIcon: (Int) -> String?,
    achievementIcon: (Int) -> String?,
    raidImage: (Int) -> String?,
) {
    val criteria = state.currentCriteria
    val query = criteria.query.trim()
    when (data) {
        is PersonalDataDashboardData.FishingSummary -> item {
            val record = data.record
            if (record == null) DashboardEmpty() else DashboardMetrics(listOf(
                R.string.personal_data_metric_total_catches to dashboardNumber(record.casts),
                R.string.personal_data_metric_success_rate to dashboardPercent(record.successRate),
                R.string.personal_data_metric_ocean_trips to dashboardNumber(record.seaTrips),
                R.string.personal_data_metric_ocean_score to dashboardNumber(record.highestSeaScore),
            ))
        }
        is PersonalDataDashboardData.GlamourSummary -> item {
            val record = data.record
            if (record == null) DashboardEmpty() else DashboardMetrics(listOf(
                R.string.personal_data_metric_washings to dashboardNumber(record.fantasiaUses),
                R.string.personal_data_metric_dyes to dashboardNumber(record.dyesUsed),
                R.string.personal_data_metric_glamours to dashboardNumber(record.projections),
            ))
            dashboardSetsState(state)?.setsProgress?.let {
                Text(stringResource(R.string.pdr_sets_progress, it.completed, it.total), Modifier.padding(top = 12.dp))
            }
        }
        is PersonalDataDashboardData.SavageSummary -> item {
            val record = data.record
            if (record == null) DashboardEmpty() else DashboardMetrics(listOf(
                R.string.personal_data_metric_raids to dashboardNumber(record.territoriesCleared),
                R.string.personal_data_metric_entries to dashboardNumber(record.entries),
                R.string.personal_data_metric_clears to dashboardNumber(record.clears),
                R.string.personal_data_metric_elapsed to (record.elapsedHours?.let { stringResource(R.string.pdd_hours, it) } ?: stringResource(R.string.pdr_unknown)),
            ))
        }
        is PersonalDataDashboardData.FishRanking -> {
            readingLink(PersonalDataReadingPage.Fish, openReading)
            dashboardRows(data.rows.filter { it.name.contains(query, true) && (criteria.category == null || it.category.orEmpty() == criteria.category) }
                .sortedByDescending { it.count }, criteria.visibleLimit, more) { row, index ->
                DashboardRecord(row.name, itemIcon = null, tag = "dashboard-fish-$index") {
                    row.category?.let { Text(dashboardCategoryLabel(it)) }
                    Text(stringResource(R.string.pdr_fish_count, row.count))
                }
            }
        }
        is PersonalDataDashboardData.BaitRanking -> {
            readingLink(PersonalDataReadingPage.Baits, openReading)
            dashboardRows(data.rows.filter { it.name.contains(query, true) && (criteria.category == null || it.category.orEmpty() == criteria.category) }
                .sortedByDescending { it.count }, criteria.visibleLimit, more) { row, index ->
                DashboardRecord(row.name, itemIcon = null, tag = "dashboard-bait-$index") {
                    row.category?.let { Text(dashboardCategoryLabel(it)) }
                    Text(stringResource(R.string.pdr_bait_count, row.count))
                }
            }
        }
        is PersonalDataDashboardData.BigFish -> {
            val rows = PersonalDataDashboardCatalogViews.fishRows(data.rows, state.catalogs, state.supplementary,
                criteria.fishGroup == PersonalDataDashboardFishGroup.Ocean, criteria.patch, criteria.includeUnobtained,
                criteria.fishSort == PersonalDataDashboardFishSort.CountDescending, query)
            dashboardRows(rows, criteria.visibleLimit, more) { row, index ->
                DashboardRecord(row.name, row.iconId?.let(itemIcon), "dashboard-big-fish-$index") {
                    row.patch?.let { Text(stringResource(R.string.pdd_patch, it)) }
                    if (row.itemId == null) Text(stringResource(R.string.pdd_unmapped))
                    val record = row.record
                    if (record == null) Text(stringResource(R.string.pdd_not_caught)) else {
                        Text(stringResource(R.string.pdd_caught_on, dashboardDate(record.caughtAt)))
                        Text(record.count?.let { stringResource(R.string.pdr_fish_count, it) } ?: stringResource(R.string.pdr_unknown))
                    }
                }
            }
        }
        is PersonalDataDashboardData.FishingAchievements -> {
            val rows = PersonalDataDashboardCatalogViews.achievementRows(data.rows, state.supplementary?.fishingAchievements.orEmpty(), criteria.includeUnobtained, query)
            dashboardRows(rows, criteria.visibleLimit, more) { row, index ->
                DashboardRecord(row.record?.name ?: row.catalog?.name ?: stringResource(R.string.pdd_achievement_number, row.achievementId),
                    row.catalog?.iconId?.let(achievementIcon), "dashboard-achievement-$index") {
                    (row.record?.detail ?: row.catalog?.detail)?.let { Text(it) }
                    val record = row.record
                    if (record == null) Text(stringResource(R.string.pdr_not_collected))
                    else Text(stringResource(R.string.pdr_recorded_on, dashboardDate(record.obtainedAt)))
                }
            }
        }
        is PersonalDataDashboardData.OceanFishing -> {
            dashboardRows(data.rows.sortedWith(compareByDescending<PersonalDataOceanRoute> { it.territoryId == 1163 }.thenBy { it.territoryId }), criteria.visibleLimit, more) { row, index ->
                DashboardRecord(when (row.territoryId) {
                    900 -> stringResource(R.string.pdd_nearshore)
                    1163 -> stringResource(R.string.pdd_offshore)
                    else -> stringResource(R.string.pdd_route_number, row.territoryId)
                }, null, "dashboard-ocean-$index") {
                    DashboardValue(R.string.personal_data_metric_ocean_trips, dashboardNumber(row.trips))
                    DashboardValue(R.string.personal_data_metric_ocean_score, dashboardNumber(row.highestScore))
                }
            }
        }
        is PersonalDataDashboardData.Races -> {
            readingLink(PersonalDataReadingPage.Races, openReading)
            val rows = data.rows.filter { "${it.usage.race} ${it.usage.gender}".contains(query, true) }
                .sortedWith(compareBy<PersonalDataRankedRace> { it.rank == null }.thenBy { it.rank })
            dashboardRows(rows, criteria.visibleLimit, more) { row, index ->
                val race = row.usage
                DashboardRecord("${race.race} ${race.gender}", null, "dashboard-race-$index") {
                    if (race.isCurrent) Text(stringResource(R.string.pdr_current), fontWeight = FontWeight.Bold)
                    if (race.isMostUsed) Text(stringResource(R.string.pdr_most_used))
                    Text(dashboardPercent(race.proportion))
                    Text(race.days?.let { stringResource(R.string.pdr_days, it) } ?: stringResource(R.string.pdr_unknown))
                }
            }
        }
        is PersonalDataDashboardData.Stains -> {
            val catalogs = state.catalogs?.glamour?.stains.orEmpty().associateBy { it.stainId }
            val rows = data.rows.filter { catalogs[it.stainId]?.name.orEmpty().contains(query, true) || it.stainId.toString().contains(query) }
                .sortedWith(compareBy<PersonalDataStainUsage> { it.rank == null }.thenBy { it.rank })
            dashboardRows(rows, criteria.visibleLimit, more) { row, index ->
                val stain = catalogs[row.stainId]
                DashboardRecord(stain?.name ?: stringResource(R.string.pdd_stain_number, row.stainId), null, "dashboard-stain-$index") {
                    if (stain != null) {
                        if (stain.stainId != 0) Box(Modifier.size(32.dp).background(Color(0xff000000L or (stain.color and 0xffffffL))))
                        if (stain.isMetallic) Text(stringResource(R.string.pdd_metallic))
                    } else Text(stringResource(R.string.pdd_unmapped))
                    Text(row.count?.let { stringResource(R.string.pdr_bait_count, it) } ?: stringResource(R.string.pdr_unknown))
                }
            }
        }
        is PersonalDataDashboardData.Accessories -> {
            val catalogs = state.catalogs?.glamour?.fashionAccessories.orEmpty().associateBy { it.id }
            val rows = data.rows.filter { catalogs[it.accessoryId]?.name.orEmpty().contains(query, true) || it.accessoryId.toString().contains(query) }
                .sortedWith(compareBy<PersonalDataAccessoryUsage> { it.rank == null }.thenBy { it.rank })
            dashboardRows(rows, criteria.visibleLimit, more) { row, index ->
                val accessory = catalogs[row.accessoryId]
                DashboardRecord(accessory?.name ?: stringResource(R.string.pdd_accessory_number, row.accessoryId), accessory?.iconId?.let(itemIcon), "dashboard-accessory-$index") {
                    if (accessory == null) Text(stringResource(R.string.pdd_unmapped))
                    Text(row.count?.let { stringResource(R.string.pdr_bait_count, it) } ?: stringResource(R.string.pdr_unknown))
                }
            }
        }
        is PersonalDataDashboardData.Vanity -> {
            val catalog = state.supplementary?.vanityCategories.orEmpty()
            val rows = PersonalDataDashboardCatalogViews.vanityRows(data.rows, catalog, criteria.vanityPeriod, criteria.vanityMajor, criteria.vanityCategoryId, query)
            val unmapped = if (criteria.vanityCategoryId == null) PersonalDataDashboardCatalogViews.unmappedVanityRows(data.rows, catalog, criteria.vanityPeriod, query) else emptyList()
            dashboardRows(rows + unmapped, criteria.visibleLimit, more) { row, index ->
                DashboardRecord(row.name ?: row.itemId?.let { stringResource(R.string.pdr_item_number, it) } ?: stringResource(R.string.pdr_unknown),
                    row.iconId?.let(itemIcon), "dashboard-vanity-$index") {
                    Text(catalog.firstOrNull { it.id == row.categoryId }?.name ?: stringResource(R.string.pdd_unmapped))
                    Text(row.count?.let { stringResource(R.string.pdr_bait_count, it) } ?: stringResource(R.string.pdr_unknown))
                }
            }
        }
        is PersonalDataDashboardData.Sets -> {
            readingLink(PersonalDataReadingPage.Sets, openReading)
            val readingState = dashboardSetsState(state)
            val rows = readingState?.visibleSets.orEmpty()
            item { readingState?.setsProgress?.let { Text(stringResource(R.string.pdr_sets_progress, it.completed, it.total)) } }
            dashboardRows(rows, criteria.visibleLimit, more) { row, index ->
                DashboardRecord(row.catalog?.name ?: stringResource(R.string.pdr_set_number, row.setId), row.catalog?.iconId?.let(itemIcon), "dashboard-set-$index") {
                    Text(stringResource(when (row.completion) {
                        PersonalDataSetCompletion.Complete -> R.string.pdr_complete
                        PersonalDataSetCompletion.Partial -> R.string.pdr_partial
                        PersonalDataSetCompletion.Unrecorded -> R.string.pdr_unrecorded
                        PersonalDataSetCompletion.Unknown -> R.string.pdr_status_unknown
                    }))
                    row.catalog?.let { Text(stringResource(R.string.pdr_item_progress, row.knownItemIds.size, it.items.map { item -> item.itemId }.distinct().size)) }
                    row.record?.let { Text(stringResource(R.string.pdr_recorded_on, dashboardDate(it.recordedAt))) }
                }
            }
        }
        is PersonalDataDashboardData.SavageRaids -> {
            val series = PersonalDataDashboardCatalogViews.savageSeries(data.rows, state.catalogs, query)
            val unmapped = PersonalDataDashboardCatalogViews.unmappedSavageRecords(data.rows, state.catalogs)
                .filter { it.territoryId.toString().contains(query) || it.jobName.orEmpty().contains(query, true) }
            if (series.isEmpty() && unmapped.isEmpty()) item { DashboardEmpty() }
            itemsIndexed(series) { index, seriesRow ->
                Column(Modifier.testTag("dashboard-savage-series-$index"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(seriesRow.catalog.name, style = MaterialTheme.typography.titleLarge)
                    seriesRow.tiers.forEach { tier -> SavageTierCard(tier, raidImage) }
                }
            }
            itemsIndexed(unmapped) { index, record ->
                DashboardRecord(stringResource(R.string.pdd_raid_number, record.territoryId), null, "dashboard-unmapped-raid-$index") {
                    Text(stringResource(R.string.pdd_unmapped))
                    SavageRecord(record, achievementOnly = false)
                }
            }
        }
    }
}

private fun dashboardSetsState(state: PersonalDataDashboardUiState): PersonalDataReadingUiState? {
    val records = (state.sections[PersonalDataDashboardSectionKind.Sets]?.data as? PersonalDataDashboardData.Sets)?.rows ?: return null
    return PersonalDataReadingUiState(activePage = PersonalDataReadingPage.Sets,
        pages = mapOf(PersonalDataReadingPage.Sets to PersonalDataReadingPageState(hasLoaded = true, setRecords = records,
            criteria = PersonalDataReadingCriteria(query = state.currentCriteria.query, visibleLimit = Int.MAX_VALUE))),
        catalogs = state.catalogs)
}

private fun LazyListScope.readingLink(page: PersonalDataReadingPage, open: (PersonalDataReadingPage) -> Unit) {
    item { OutlinedButton({ open(page) }, Modifier.fillMaxWidth().testTag("dashboard-open-${page.name}")) { Text(stringResource(page.openLabel())) } }
}

private fun <T> LazyListScope.dashboardRows(rows: List<T>, limit: Int, more: () -> Unit, content: @Composable (T, Int) -> Unit) {
    if (rows.isEmpty()) item { DashboardEmpty() }
    itemsIndexed(rows.take(limit)) { index, row -> content(row, index) }
    if (rows.isNotEmpty()) item {
        Text(stringResource(R.string.pdr_shown, minOf(limit, rows.size), rows.size), Modifier.testTag("dashboard-shown"))
        if (rows.size > limit) OutlinedButton(more, Modifier.fillMaxWidth().testTag("dashboard-more")) { Text(stringResource(R.string.pdr_show_more)) }
    }
}

@Composable
private fun DashboardEmpty() = Text(stringResource(R.string.pdr_empty), Modifier.testTag("dashboard-empty"))

@Composable
internal fun DashboardMetrics(values: List<Pair<Int, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        values.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                        Text(value, style = MaterialTheme.typography.headlineSmall)
                    } }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun DashboardRecord(title: String, itemIcon: String?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemIcon?.let { AsyncImage(it, null, Modifier.size(48.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}

@Composable
internal fun DashboardValue(label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(value)
    }
}

@Composable
private fun SavageTierCard(tier: DashboardSavageTier, image: (Int) -> String?) {
    Card(Modifier.fillMaxWidth().testTag("dashboard-savage-tier-${tier.catalog.raids.firstOrNull()?.instanceId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (LocalConfiguration.current.locales[0].language == "zh") tier.catalog.nameChinese else tier.catalog.nameEnglish,
                style = MaterialTheme.typography.titleMedium)
            if (tier.catalog.achievementOnly) {
                tier.catalog.achievementText?.let { Text(it) }
                val last = tier.raids.lastOrNull()
                last?.catalog?.imageId?.let(image)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().heightIn(max = 160.dp), contentScale = ContentScale.Fit) }
                val records = last?.records.orEmpty()
                if (records.isEmpty()) Text(stringResource(R.string.personal_data_not_cleared))
                else records.forEach { SavageRecord(it, achievementOnly = true) }
            } else {
                if (tier.isComplete) Text(stringResource(R.string.pdd_tier_complete), fontWeight = FontWeight.Bold)
                tier.raids.forEach { raid ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        raid.catalog.imageId?.let(image)?.let { AsyncImage(it, null, Modifier.width(96.dp).height(56.dp), contentScale = ContentScale.Crop) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(raid.catalog.name, fontWeight = FontWeight.SemiBold)
                            if (raid.records.isEmpty()) Text(stringResource(R.string.personal_data_not_cleared))
                            else raid.records.forEach { SavageRecord(it, achievementOnly = false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavageRecord(record: PersonalDataSavageClear, achievementOnly: Boolean) {
    Text(stringResource(R.string.pdd_cleared_on, dashboardDate(record.clearedAt)))
    if (!achievementOnly) {
        record.jobName?.let { DashboardValue(R.string.personal_data_job, it) }
        record.elapsedSeconds?.let { Text(stringResource(R.string.pdd_seconds, it)) }
        if (record.supportsUnrestricted == true) Text(stringResource(R.string.pdd_unrestricted_supported), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun dashboardDate(date: Instant?): String = date?.let {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0])
        .withZone(ZoneId.systemDefault()).format(it)
} ?: stringResource(R.string.pdr_unknown)

@Composable
internal fun dashboardNumber(number: Long?): String = number?.let { NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(it) } ?: stringResource(R.string.pdr_unknown)

@Composable
internal fun dashboardPercent(ratio: Double?): String = ratio?.takeIf { it.isFinite() && it in 0.0..1.0 }
    ?.let { stringResource(R.string.pdd_percent, it * 100.0) } ?: stringResource(R.string.pdr_unknown)
