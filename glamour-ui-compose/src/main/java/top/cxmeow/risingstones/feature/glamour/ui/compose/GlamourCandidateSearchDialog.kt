package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cxmeow.risingstones.feature.glamour.domain.GlamourSearchSelection
import top.cxmeow.risingstones.feature.glamour.domain.GlamourService
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourCandidateKind
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourCandidateSearchViewModel
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourCandidateSearchViewModelFactory

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlamourCandidateSearchDialog(
    service: GlamourService,
    onSelect: (GlamourSearchSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val model: GlamourCandidateSearchViewModel = viewModel(
        key = "glamour-candidate-search-${System.identityHashCode(service)}",
        factory = remember(service) { GlamourCandidateSearchViewModelFactory(service) },
    )
    val state by model.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    val dismiss = { model.resetCancel(); onDismiss() }
    AlertDialog(
        modifier = Modifier.widthIn(max = 640.dp),
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.glamour_find_by_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlamourCandidateKind.entries.forEach { kind -> FilterChip(
                        selected = kind == state.kind,
                        onClick = { model.search(kind, query) },
                        label = { Text(stringResource(when (kind) {
                            GlamourCandidateKind.Equipment -> R.string.glamour_equipment
                            GlamourCandidateKind.Glasses -> R.string.glamour_face_accessory
                            GlamourCandidateKind.Ornament -> R.string.glamour_fashion_accessory
                        })) },
                    ) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.glamour_item_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { model.search(state.kind, query) }, enabled = query.isNotBlank(), modifier = Modifier.testTag("glamour-candidate-submit")) {
                    Text(stringResource(R.string.glamour_search))
                }
                if (state.isLoading || state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error != null) {
                    Text(glamourErrorMessage(state.error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.search(state.kind, state.query) }) { Text(stringResource(R.string.glamour_retry)) }
                }
                if (state.hasLoaded && !state.isLoading && state.error == null && state.items.isEmpty()) {
                    Text(stringResource(R.string.glamour_no_matching_items))
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp).testTag("glamour-candidate-list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.items, key = { it.id }) { candidate ->
                        TextButton(onClick = {
                            runCatching { model.selection(candidate) }.getOrNull()?.let { selection ->
                                model.resetCancel()
                                onSelect(selection)
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(candidate.name, style = MaterialTheme.typography.titleSmall)
                                candidate.groupName?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                if (candidate.jobNames.isNotEmpty()) Text(candidate.jobNames.joinToString(), style = MaterialTheme.typography.labelSmall)
                                if (candidate.description.isNotBlank()) Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.hasNextPage) item {
                        TextButton(onClick = model::loadMore, enabled = !state.isLoadingMore) {
                            Text(stringResource(R.string.glamour_more))
                        }
                    }
                    if (state.isLoadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.glamour_cancel)) } },
    )
}
