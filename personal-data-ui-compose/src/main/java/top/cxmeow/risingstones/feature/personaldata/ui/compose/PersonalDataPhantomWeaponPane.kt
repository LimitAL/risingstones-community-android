package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.presentation.*

@Composable
internal fun PhantomWeaponPane(
    state: PhantomWeaponUiState,
    model: ExplorationViewModel,
    scroll: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("phantom-pane")) {
        Text(stringResource(R.string.pdw_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall)
        val stageScroll = rememberLazyListState()
        LaunchedEffect(state.selectedStage) { state.selectedStage?.let { stageScroll.scrollToItem(it.ordinal) } }
        LazyRow(Modifier.testTag("phantom-stages"), state = stageScroll, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PhantomWeaponStage.entries) { stage ->
                FilterChip(selected = stage == state.selectedStage, onClick = { model.selectPhantomWeaponStage(stage) },
                    enabled = stage in state.availableStages,
                    label = { Text(stringResource(stage.weaponLabel())) }, modifier = Modifier.testTag("phantom-stage-$stage"))
            }
        }
        key(state.selectedStage) {
            val anchor = remember(scroll) { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
            LaunchedEffect(scroll) {
                if (anchor.first != 0 || anchor.second != 0) {
                    snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it > 0 }
                    withFrameNanos { }; withFrameNanos { }
                    if (!scroll.isScrollInProgress) scroll.requestScrollToItem(anchor.first, anchor.second)
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("phantom-list"), state = scroll,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isLoading || state.isLoadingCatalog) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("phantom-loading"))
                }
                if (state.catalogError != null) item {
                    PhantomFailure(R.string.pdw_catalog_failure, "phantom-catalog-error", model::retryPhantomWeaponCatalog)
                }
                if (state.itemError != null || state.aetherError != null) item {
                    PhantomFailure(R.string.pdw_records_failure, "phantom-records-error", model::loadOverview)
                }
                if (state.maximumStage == null && !state.isLoading && !state.isLoadingCatalog) item {
                    Text(stringResource(if (state.hasUnknownProgress)
                        R.string.pdw_progress_unknown else R.string.pdw_no_progress), Modifier.testTag("phantom-no-progress"))
                }
                if (state.selectedStage != null) {
                    item { PhantomMaterials(state.materials, model) }
                    item {
                        Text(stringResource(R.string.pdw_collection, state.acquiredCount, state.weapons.size),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("phantom-collection"))
                        state.recentWeapon?.let { recent ->
                            Text(stringResource(R.string.pdw_recent, recent.definition.name), Modifier.testTag("phantom-recent"))
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(state.query, model::setPhantomWeaponQuery, singleLine = true,
                                label = { Text(stringResource(R.string.pdr_search)) },
                                modifier = Modifier.fillMaxWidth().testTag("phantom-search"))
                            FilterChip(state.obtainedOnly, { model.setPhantomWeaponsObtainedOnly(!state.obtainedOnly) },
                                label = { Text(stringResource(R.string.pdw_obtained_only)) }, modifier = Modifier.testTag("phantom-obtained-only"))
                        }
                    }
                    if (state.visibleWeapons.isEmpty()) item {
                        Text(stringResource(R.string.pdw_no_matches), Modifier.testTag("phantom-empty-filter"))
                    }
                    items(state.visibleWeapons, key = { it.definition.itemId }) { weapon ->
                        PhantomWeaponCard(weapon, model.phantomWeaponItemIconUrl(weapon.definition.iconId))
                    }
                    if (state.hasMoreWeapons || state.isExpanded) item {
                        OutlinedButton({ model.setPhantomWeaponsExpanded(!state.isExpanded) },
                            Modifier.fillMaxWidth().testTag("phantom-expand")) {
                            Text(stringResource(if (state.isExpanded) R.string.pdw_collapse else R.string.pdw_expand))
                        }
                    }
                }
                item { Text(stringResource(R.string.pdw_source), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun PhantomFailure(label: Int, tag: String, retry: () -> Unit) {
    Column(Modifier.testTag(tag)) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.error)
        TextButton(retry, Modifier.testTag("$tag-retry")) { Text(stringResource(R.string.personal_data_retry)) }
    }
}

@Composable
private fun PhantomMaterials(materials: PhantomWeaponMaterials, model: ExplorationViewModel) {
    when (materials) {
        is PhantomWeaponMaterials.SoulCrystals -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pdw_soul_crystals), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.pdw_drop_count_note), style = MaterialTheme.typography.bodySmall)
                materials.rows.forEach { row -> PhantomMaterialCard(phantomMaterialName(row.definition),
                    model.phantomWeaponItemIconUrl(row.definition.iconId), row.count, row.target, row.fraction,
                    "phantom-material-${row.definition.itemId}") }
                TextButton(model::openPhantomWeaponHistory, Modifier.testTag("phantom-history")) {
                    Text(stringResource(R.string.pdw_history))
                }
            }
        }
        is PhantomWeaponMaterials.Aether -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pdw_aether), style = MaterialTheme.typography.titleMedium)
            materials.rows.forEach { row -> PhantomMaterialCard(stringResource(row.element.weaponLabel()),
                model.phantomWeaponElementIconUrl(row.element), row.points, row.target, row.fraction,
                "phantom-element-${row.element}") }
        }
        is PhantomWeaponMaterials.Lens -> {
            val progress = materials.progress
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pdw_lens), style = MaterialTheme.typography.titleMedium)
                PhantomMaterialCard(stringResource(if (progress.isComplete) R.string.pdw_complete else R.string.pdw_lens_progress),
                    progress.step?.let(model::phantomWeaponLensImageUrl), progress.current, progress.target, progress.fraction, "phantom-lens")
                Text(stringResource(R.string.pdw_cumulative, phantomNumber(progress.cumulative)), Modifier.testTag("phantom-cumulative"))
            }
        }
        is PhantomWeaponMaterials.DemiAtma -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pdw_demiatma), style = MaterialTheme.typography.titleMedium)
            materials.rows.forEach { row -> PhantomMaterialCard(phantomMaterialName(row.definition),
                model.phantomWeaponItemIconUrl(row.definition.iconId), row.count, row.target, row.fraction,
                "phantom-material-${row.definition.itemId}") }
        }
        PhantomWeaponMaterials.None -> Unit
    }
}

@Composable
private fun PhantomMaterialCard(name: String, imageUrl: String?, quantity: Long?, target: Long?, fraction: Double?, tag: String) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                imageUrl?.let { AsyncImage(it, null, Modifier.size(44.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(if (target == null) phantomNumber(quantity)
                        else stringResource(R.string.pdw_progress, phantomNumber(quantity), phantomNumber(target)))
                }
            }
            if (fraction != null) LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PhantomWeaponCard(weapon: PhantomWeaponRow, icon: String?) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("phantom-weapon-${weapon.definition.itemId}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon?.let { AsyncImage(it, null, Modifier.size(56.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(weapon.definition.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (weapon.isObtained) R.string.pdr_collected else R.string.pdr_not_collected))
                if (weapon.isObtained) Text(stringResource(R.string.pdw_first_acquired,
                    phantomDateText(weapon.record?.firstAcquiredAt, ZoneId.systemDefault(), LocalConfiguration.current.locales[0])
                        ?: stringResource(R.string.pdr_unknown)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun phantomMaterialName(definition: PhantomWeaponMaterialDefinition): String =
    definition.name.takeIf(String::isNotBlank) ?: if (definition.itemId > 0)
        stringResource(R.string.exploration_item_number, definition.itemId) else stringResource(R.string.pdr_unknown)

@Composable private fun phantomNumber(value: Long?): String = value?.let { dashboardNumber(it) } ?: stringResource(R.string.pdr_unknown)

internal fun phantomDateText(value: PhantomWeaponRecordTime?, zone: ZoneId, locale: Locale): String? = try {
    val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    when (value) {
        is PhantomWeaponRecordTime.CalendarDate -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value.value)
        is PhantomWeaponRecordTime.LocalTime -> dateTime.format(value.value)
        is PhantomWeaponRecordTime.OffsetTime -> dateTime.format(value.value.atZone(zone))
        null -> null
    }
} catch (_: DateTimeException) { null }

internal fun PhantomWeaponStage.weaponLabel(): Int = when (this) {
    PhantomWeaponStage.Penumbrae -> R.string.pdw_penumbrae
    PhantomWeaponStage.Umbrae -> R.string.pdw_umbrae
    PhantomWeaponStage.Obscurum -> R.string.pdw_obscurum
    PhantomWeaponStage.Eclipticum -> R.string.pdw_eclipticum
    PhantomWeaponStage.Occultum -> R.string.pdw_occultum
}
private fun PhantomWeaponElement.weaponLabel(): Int = when (this) {
    PhantomWeaponElement.Yellow -> R.string.exploration_yellow
    PhantomWeaponElement.Red -> R.string.exploration_red
    PhantomWeaponElement.Blue -> R.string.exploration_blue
    PhantomWeaponElement.Green -> R.string.exploration_green
}
