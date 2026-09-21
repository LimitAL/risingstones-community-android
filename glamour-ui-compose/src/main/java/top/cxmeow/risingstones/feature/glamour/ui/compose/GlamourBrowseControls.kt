package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.glamour.domain.GlamourAuthor
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFilter
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourBrowsingUiState
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourUiState
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourViewModel

@Composable
internal fun GlamourFilterDialog(
    state: GlamourUiState,
    browsing: GlamourBrowsingUiState,
    onReload: () -> Unit,
    onApply: (GlamourFilter, Int?, Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var race by rememberSaveable { mutableStateOf(state.filter.raceId) }
    var tribe by rememberSaveable { mutableStateOf(browsing.tribeId) }
    var gender by rememberSaveable { mutableStateOf(state.filter.genderId) }
    var period by rememberSaveable { mutableStateOf(state.filter.createTime) }
    var tags by rememberSaveable { mutableStateOf(browsing.tagIds.toList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.glamour_filters)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("glamour-filter-options"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (browsing.isLoadingCatalog || state.isLoadingRaces) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (browsing.catalogError != null || state.raceError != null) {
                    Text(stringResource(R.string.glamour_catalog_unavailable), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onReload) { Text(stringResource(R.string.glamour_retry)) }
                }
                GlamourFilterGroup(stringResource(R.string.glamour_filter_race)) {
                    FilterChoice(race == null, stringResource(R.string.glamour_all)) { race = null; tribe = null }
                    state.races.forEach { item ->
                        FilterChoice(race == item.id, item.name) { if (race != item.id) tribe = null; race = item.id }
                    }
                }
                val tribes = browsing.tribes.filter { it.raceId == race }
                if (tribes.isNotEmpty()) GlamourFilterGroup(stringResource(R.string.glamour_filter_tribe)) {
                    FilterChoice(tribe == null, stringResource(R.string.glamour_all)) { tribe = null }
                    tribes.forEach { item -> FilterChoice(tribe == item.id, item.name) { tribe = item.id } }
                }
                GlamourFilterGroup(stringResource(R.string.glamour_filter_gender)) {
                    FilterChoice(gender == null, stringResource(R.string.glamour_all)) { gender = null }
                    FilterChoice(gender == 1, stringResource(R.string.glamour_gender_male)) { gender = 1 }
                    FilterChoice(gender == 2, stringResource(R.string.glamour_gender_female)) { gender = 2 }
                }
                GlamourFilterGroup(stringResource(R.string.glamour_filter_time)) {
                    FilterChoice(period == null, stringResource(R.string.glamour_all)) { period = null }
                    FilterChoice(period == "last24H", stringResource(R.string.glamour_time_day)) { period = "last24H" }
                    FilterChoice(period == "lastWeek", stringResource(R.string.glamour_time_week)) { period = "lastWeek" }
                    FilterChoice(period == "lastMonth", stringResource(R.string.glamour_time_month)) { period = "lastMonth" }
                }
                browsing.tagCategories.forEach { category ->
                    val categoryIds = category.tags.map { it.id }.toSet()
                    GlamourFilterGroup(category.name) {
                        FilterChoice(tags.none { it in categoryIds }, stringResource(R.string.glamour_all)) {
                            tags = tags.filterNot { it in categoryIds }
                        }
                        category.tags.forEach { tag -> FilterChoice(tag.id in tags, tag.name) {
                            tags = tags.filterNot { it in categoryIds } + tag.id
                        } }
                    }
                }
                TextButton(onClick = { race = null; tribe = null; gender = null; period = null; tags = emptyList() }) {
                    Text(stringResource(R.string.glamour_reset_filters))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(state.filter.copy(raceId = race, genderId = gender, createTime = period), tribe, tags.toSet())
            }) { Text(stringResource(R.string.glamour_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.glamour_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlamourFilterGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun FilterChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
internal fun GlamourAuthorLink(author: GlamourAuthor, onClick: () -> Unit) {
    val label = listOf(author.characterName, author.areaName, author.groupName).filter(String::isNotBlank).joinToString(" · ")
    TextButton(onClick = onClick, enabled = !author.id.isNullOrBlank()) {
        Text(label.ifBlank { stringResource(R.string.glamour_author_works) },
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    GlamourCommunityProfileLink(author)
}

@Composable
internal fun GlamourAuthorHeader(state: GlamourUiState, fallback: GlamourAuthor, viewModel: GlamourViewModel) {
    val profile = state.authorProfile
    val author = profile?.author ?: fallback
    if (state.isLoadingAuthorProfile) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(author.characterName, style = MaterialTheme.typography.titleMedium)
    GlamourCommunityProfileLink(author)
    profile?.profile?.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (profile != null) {
        Text(stringResource(R.string.glamour_social_counts, profile.followingCount, profile.followerCount),
            style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = viewModel::toggleFollow, enabled = !state.isUpdatingFollow) {
            Text(stringResource(if (profile.isFollowing) R.string.glamour_unfollow else R.string.glamour_follow))
        }
    }
    if (state.followError != null) {
        Text(stringResource(R.string.glamour_unavailable), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = viewModel::clearFollowError) { Text(stringResource(R.string.glamour_dismiss)) }
    }
}
