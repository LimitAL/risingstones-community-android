package top.cxmeow.risingstones.feature.recruitment.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.*

@Composable
internal fun RecruitmentFilterDialog(state: DutyRecruitmentUiState, model: DutyRecruitmentViewModel,
    onDismiss: () -> Unit) {
    val browsing by model.browsingState.collectAsStateWithLifecycle()
    val extended = model.canBrowseExtended
    var dutyType by rememberSaveable { mutableStateOf(state.dutyQuery.dutyType) }
    var dutyName by rememberSaveable { mutableStateOf(state.dutyQuery.dutyName) }
    var position by rememberSaveable { mutableStateOf(state.dutyQuery.position?.name) }
    var keyword by rememberSaveable { mutableStateOf(state.communityQuery.keyword) }
    var area by rememberSaveable { mutableStateOf(state.communityQuery.areaId) }
    var server by rememberSaveable { mutableStateOf(state.communityQuery.groupId) }
    var styles by rememberSaveable { mutableStateOf(state.communityQuery.styleIds) }
    var labels by rememberSaveable { mutableStateOf(state.communityQuery.guildLabelIds) }
    var categories by rememberSaveable { mutableStateOf(state.communityQuery.categoryIds) }
    var identity by rememberSaveable { mutableStateOf(state.communityQuery.identity) }
    var positions by rememberSaveable { mutableStateOf(browsing.dutyQuery.positions.map { it.name }) }
    var composition by rememberSaveable { mutableStateOf(browsing.dutyQuery.teamComposition) }
    var dutyArea by rememberSaveable { mutableStateOf(browsing.dutyQuery.targetAreaId) }
    var dutyLabels by rememberSaveable { mutableStateOf(browsing.dutyQuery.labelIds) }
    var allianceTeam by rememberSaveable { mutableStateOf(browsing.dutyQuery.allianceTeamKey) }
    var activeMembers by rememberSaveable { mutableStateOf(state.communityQuery.activeMemberCounts) }
    var rpTypes by rememberSaveable { mutableStateOf(state.communityQuery.rolePlayTypes) }
    var rpStatus by rememberSaveable { mutableStateOf(state.communityQuery.rolePlayStatus) }
    var rpOrder by rememberSaveable { mutableStateOf(state.communityQuery.order) }
    val duty = state.board == RecruitmentBoardKind.Duty
    val multipleServers = state.board == RecruitmentBoardKind.RolePlay || state.board == RecruitmentBoardKind.Other
    AlertDialog(
        modifier = Modifier.widthIn(max = 680.dp).imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recruitment_filters)) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())
                .testTag("recruitment-filter-options"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (if (duty) state.isLoadingCatalogs else state.isLoadingFilterCatalog) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (if (duty) state.catalogsError != null else state.filterCatalogError != null) {
                    Text(stringResource(R.string.recruitment_catalog_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { if (duty) model.loadCatalogs() else model.retryFilterCatalog() }) {
                        Text(stringResource(R.string.recruitment_retry))
                    }
                }
                if (duty) {
                    RecruitmentChoice(R.string.recruitment_duty_type, dutyType,
                        (state.catalogs.dutyTypes + "其他").distinct().map {
                            it to if (it == "其他") stringResource(R.string.recruitment_other_duty) else it
                        }, "duty-type") { next ->
                        if (next != dutyType) {
                            dutyName = ""; position = null; positions = emptyList(); allianceTeam = ""
                            composition = dutyRecruitmentTeamCompositionForType(next)
                        }
                        dutyType = next
                    }
                    if (dutyType == "其他") {
                        OutlinedTextField(dutyName, { if (it.length <= 15) dutyName = it },
                            label = { Text(stringResource(R.string.recruitment_custom_duty_hint)) },
                            modifier = Modifier.fillMaxWidth().testTag("recruitment-custom-duty"))
                    } else RecruitmentChoice(R.string.recruitment_duty_name, dutyName,
                        state.catalogs.dutyNames(dutyType).map { it to it }, "duty-name") { next ->
                        if (dutyType.isBlank() && next.isNotBlank()) {
                            dutyType = state.catalogs.duties.firstOrNull { it.dutyName == next }?.dutyType.orEmpty()
                            composition = dutyRecruitmentTeamCompositionForType(dutyType)
                            positions = emptyList(); allianceTeam = ""
                        }
                        dutyName = next
                    }
                    if (extended) {
                        RecruitmentChoice(R.string.recruitment_team_composition, composition,
                            listOf("轻锐小队" to stringResource(R.string.recruitment_light_party),
                                "满编小队" to stringResource(R.string.recruitment_full_party),
                                "团队" to stringResource(R.string.recruitment_alliance)), "composition",
                            enabled = dutyType.isBlank() || dutyType == "其他") {
                            if (composition != it) { positions = emptyList(); allianceTeam = "" }
                            composition = it
                        }
                        val positionOptions = DutyRecruitmentPosition.optionsForTeamComposition(composition)
                        if (positionOptions.isNotEmpty()) RecruitmentMultipleChoices(R.string.recruitment_position,
                            positions, positionOptions.map { CommunityRecruitmentFilterOption(it.name, it.wireValue) }) {
                            positions = it
                        }
                        if (composition == "团队") RecruitmentChoice(R.string.recruitment_alliance_team,
                            allianceTeam, listOf("A" to "A", "B" to "B", "C" to "C"), "alliance-team") { allianceTeam = it }
                        RecruitmentChoice(R.string.recruitment_area, dutyArea,
                            browsing.dutyFilterCatalog?.areas.orEmpty().filter { it.id != "0" && it.id != "-1" }.map { it.id to it.name } +
                                ("-1" to stringResource(R.string.recruitment_international_area)), "duty-area") { dutyArea = it }
                        RecruitmentMultipleChoices(R.string.recruitment_labels, dutyLabels,
                            browsing.dutyFilterCatalog?.labels.orEmpty().map { CommunityRecruitmentFilterOption(it.id, it.name) }) { dutyLabels = it }
                    } else {
                        RecruitmentChoice(R.string.recruitment_position, position.orEmpty(),
                            DutyRecruitmentPosition.options(dutyType).map { it.name to it.wireValue }, "position") {
                            position = it.takeIf(String::isNotEmpty)
                        }
                    }
                } else {
                    if (state.board in setOf(RecruitmentBoardKind.Guild, RecruitmentBoardKind.RolePlay)) {
                        OutlinedTextField(keyword, { keyword = it },
                            label = { Text(stringResource(R.string.recruitment_keyword)) },
                            modifier = Modifier.fillMaxWidth().testTag("recruitment-filter-keyword"), singleLine = true)
                    }
                    RecruitmentChoice(R.string.recruitment_area, area,
                        state.filterCatalog.areas.map { it.id to it.name }, "area") {
                        if (it != area) server = ""
                        area = it
                    }
                    val serverOptions = state.filterCatalog.areas.firstOrNull { it.id == area }?.servers.orEmpty()
                        .filter { it.id != "0" }
                    if (multipleServers && area.isNotBlank() && area != "0") {
                        RecruitmentServerChoices(server, serverOptions.map { it.id to it.name }) { server = it }
                    } else if (!multipleServers) RecruitmentChoice(R.string.recruitment_server, server,
                        serverOptions.map { it.id to it.name }, "server") { server = it }
                    if (state.board == RecruitmentBoardKind.Beginner) {
                        RecruitmentChoice(R.string.recruitment_identity, identity, listOf(
                            "1" to stringResource(R.string.recruitment_seek_newcomer),
                            "2" to stringResource(R.string.recruitment_seek_mentor)), "identity") { identity = it }
                        RecruitmentMultipleChoices(R.string.recruitment_styles, styles, state.filterCatalog.styles) { styles = it }
                    }
                    if (state.board == RecruitmentBoardKind.Guild) {
                        RecruitmentChoice(R.string.recruitment_active_members, activeMembers,
                            listOf("0-5", "6-10", "11-20", "21-50", "51-100").map { it to it } +
                                ("101-9999999" to stringResource(R.string.recruitment_over_100_members)), "active-members") { activeMembers = it }
                        RecruitmentMultipleChoices(R.string.recruitment_labels, labels, state.filterCatalog.guildLabels) { labels = it }
                    }
                    if (state.board == RecruitmentBoardKind.Other) {
                        RecruitmentMultipleChoices(R.string.recruitment_categories, categories, state.filterCatalog.categories) { categories = it }
                    }
                    if (state.board == RecruitmentBoardKind.RolePlay) {
                        RecruitmentMultipleChoices(R.string.recruitment_roleplay_types, rpTypes, listOf(
                            CommunityRecruitmentFilterOption("0", stringResource(R.string.recruitment_roleplay_none)),
                            CommunityRecruitmentFilterOption("1", stringResource(R.string.recruitment_roleplay_light)),
                            CommunityRecruitmentFilterOption("2", stringResource(R.string.recruitment_roleplay_medium)),
                            CommunityRecruitmentFilterOption("3", stringResource(R.string.recruitment_roleplay_heavy)))) { rpTypes = it }
                        RecruitmentChoice(R.string.recruitment_activity_status, rpStatus, listOf(
                            "1" to stringResource(R.string.recruitment_activity_ongoing),
                            "0" to stringResource(R.string.recruitment_activity_upcoming),
                            "-1" to stringResource(R.string.recruitment_activity_not_held)), "activity-status") { rpStatus = it }
                        RecruitmentChoice(R.string.recruitment_list_order, rpOrder, listOf(
                            "hottest" to stringResource(R.string.recruitment_hottest),
                            "scoreDesc" to stringResource(R.string.recruitment_highest_score),
                            "latest" to stringResource(R.string.recruitment_latest),
                            "earliest" to stringResource(R.string.recruitment_earliest)), "list-order") { rpOrder = it }
                    }
                }
                TextButton(onClick = {
                    dutyType = ""; dutyName = ""; position = null; keyword = ""; area = ""; server = ""
                    styles = emptyList(); labels = emptyList(); categories = emptyList(); identity = ""
                    positions = emptyList(); composition = ""; dutyArea = ""; dutyLabels = emptyList(); allianceTeam = ""
                    activeMembers = ""; rpTypes = emptyList(); rpStatus = ""; rpOrder = ""
                }) { Text(stringResource(R.string.recruitment_reset_filters)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (duty && extended) model.applyDutyBrowseFilter(browsing.dutyQuery.copy(
                    list = state.dutyQuery.copy(dutyType = dutyType, dutyName = dutyName, position = null),
                    positions = positions.mapNotNull { name -> DutyRecruitmentPosition.entries.firstOrNull { it.name == name } },
                    teamComposition = composition, targetAreaId = dutyArea, labelIds = dutyLabels, allianceTeamKey = allianceTeam))
                else if (duty) model.applyDutyFilter(dutyType, dutyName,
                    DutyRecruitmentPosition.entries.firstOrNull { it.name == position })
                else model.applyCommunityFilter(state.communityQuery.copy(keyword = keyword, areaId = area,
                    groupId = when {
                        !multipleServers -> server
                        area.isBlank() || area == "0" -> ""
                        server.isBlank() || "0" in server.split(',') -> if (state.board == RecruitmentBoardKind.Other) "0" else ""
                        else -> server
                    }, styleIds = styles, guildLabelIds = labels, categoryIds = categories, identity = identity,
                    activeMemberCounts = activeMembers, rolePlayTypes = rpTypes, rolePlayStatus = rpStatus, order = rpOrder))
                onDismiss()
            }, modifier = Modifier.testTag("recruitment-apply-filters")) { Text(stringResource(R.string.recruitment_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.recruitment_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecruitmentServerChoices(selected: String, options: List<Pair<String, String>>,
    onSelect: (String) -> Unit) {
    val ids = selected.split(',').filter(String::isNotBlank).let { if ("0" in it) emptyList() else it }
    Text(stringResource(R.string.recruitment_server), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("recruitment-filter-servers")) {
        FilterChip(ids.isEmpty(), { onSelect("") },
            modifier = Modifier.testTag("recruitment-filter-server-all"),
            label = { Text(stringResource(R.string.recruitment_all_servers)) })
        options.forEach { (id, name) ->
            FilterChip(id in ids, {
                val next = if (id !in ids) ids + id else if (ids.size > 1) ids - id else ids
                onSelect(next.joinToString(","))
            }, modifier = Modifier.testTag("recruitment-filter-server-$id"), label = { Text(name) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecruitmentChoice(label: Int, selected: String, options: List<Pair<String, String>>,
    tag: String, enabled: Boolean = true, allowAll: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val all = stringResource(R.string.recruitment_all)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(options.firstOrNull { it.first == selected }?.second ?: selected.ifEmpty { all }, {},
            readOnly = true, enabled = enabled,
            label = { Text(stringResource(label)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("recruitment-filter-$tag"))
        ExposedDropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            if (allowAll) DropdownMenuItem(text = { Text(all) }, onClick = { onSelect(""); expanded = false })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecruitmentMultipleChoices(label: Int, selected: List<String>,
    options: List<CommunityRecruitmentFilterOption>, onSelect: (List<String>) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected.isEmpty(), { onSelect(emptyList()) }, label = { Text(stringResource(R.string.recruitment_all)) })
        options.forEach { item -> FilterChip(item.id in selected,
            { onSelect(if (item.id in selected) selected - item.id else selected + item.id) },
            modifier = Modifier.testTag("recruitment-filter-option-${item.id}"), label = { Text(item.name) }) }
    }
}
