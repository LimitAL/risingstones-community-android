package top.cxmeow.risingstones.feature.glamour.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFavoriteFolder
import top.cxmeow.risingstones.feature.glamour.domain.GlamourFolderNameMaximumLength
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourInteractionUiState
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourUiState
import top.cxmeow.risingstones.feature.glamour.presentation.GlamourViewModel

@Composable
internal fun GlamourFolderManagerDialog(
    state: GlamourUiState,
    interactions: GlamourInteractionUiState,
    model: GlamourViewModel,
    onDismiss: () -> Unit,
) {
    var editingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var isCreating by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var consumedRevision by rememberSaveable { mutableStateOf(interactions.folderMutationRevision) }
    LaunchedEffect(interactions.folderMutationRevision) {
        if (consumedRevision != interactions.folderMutationRevision) {
            isCreating = false
            editingId = null
            deletingId = null
            consumedRevision = interactions.folderMutationRevision
        }
    }
    val saving = state.isCreatingFolder || interactions.updatingFolderId != null || state.deletingFolderId != null
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.glamour_manage_folders)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FolderRefreshNotice(interactions.folderRefreshError, model::retryFolderRefresh)
                if (state.isLoadingFolders) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.folders, key = { it.id }) { folder ->
                        Column {
                            Text(folder.name, style = MaterialTheme.typography.titleMedium)
                            FolderVisibility(folder)
                            Row {
                                val editLabel = stringResource(R.string.glamour_edit_folder_named, folder.name)
                                val deleteLabel = stringResource(R.string.glamour_delete_folder_named, folder.name)
                                if (model.supportsCollectionManagement) TextButton(enabled = !saving,
                                    modifier = Modifier.testTag("glamour-folder-edit-${folder.id}").semantics { contentDescription = editLabel }, onClick = {
                                    model.clearNotice(); editingId = folder.id
                                }) { Text(stringResource(R.string.glamour_edit_folder)) }
                                if (!folder.isDefault) TextButton(enabled = !saving,
                                    modifier = Modifier.testTag("glamour-folder-delete-${folder.id}").semantics { contentDescription = deleteLabel }, onClick = {
                                    model.clearNotice(); deletingId = folder.id
                                }) { Text(stringResource(R.string.glamour_delete_folder)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            model.clearNotice(); isCreating = true
        }) { Text(stringResource(R.string.glamour_new_folder)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.glamour_done)) } },
    )
    if (isCreating) GlamourFolderFormDialog(null, state.isCreatingFolder, state.folderMutationError,
        onSave = { name, public -> model.createFolder(name, public) },
        onDismiss = { isCreating = false })
    state.folders.firstOrNull { it.id == editingId }?.let { folder ->
        GlamourFolderFormDialog(folder, interactions.updatingFolderId == folder.id, state.folderMutationError,
            onSave = { name, public -> model.updateFolder(folder.id, name, public) },
            onDismiss = { editingId = null })
    }
    state.folders.firstOrNull { it.id == deletingId && !it.isDefault }?.let { folder ->
        val deleting = state.deletingFolderId == folder.id
        AlertDialog(
            onDismissRequest = { if (!deleting) deletingId = null },
            title = { Text(stringResource(R.string.glamour_delete_folder)) },
            text = { Column {
                Text(stringResource(R.string.glamour_delete_folder_confirm, folder.name))
                state.folderMutationError?.let { Text(glamourErrorMessage(it), color = MaterialTheme.colorScheme.error) }
                if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                model.deleteFolder(folder.id)
            }) { Text(stringResource(R.string.glamour_confirm_delete)) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deletingId = null }) {
                Text(stringResource(R.string.glamour_cancel))
            } },
        )
    }
}

@Composable
internal fun GlamourFavoritePicker(
    state: GlamourUiState,
    interactions: GlamourInteractionUiState,
    model: GlamourViewModel,
) {
    var isCreating by rememberSaveable { mutableStateOf(false) }
    var consumedRevision by rememberSaveable { mutableStateOf(interactions.folderMutationRevision) }
    LaunchedEffect(interactions.folderMutationRevision) {
        if (consumedRevision != interactions.folderMutationRevision) {
            isCreating = false
            consumedRevision = interactions.folderMutationRevision
        }
    }
    val busy = interactions.isSubmittingFavorite || state.isCreatingFolder
    AlertDialog(
        onDismissRequest = { if (!busy) model.closeFavoritePicker() },
        title = { Text(stringResource(R.string.glamour_choose_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (interactions.isLoadingFavoriteFolders || interactions.isSubmittingFavorite) LinearProgressIndicator(Modifier.fillMaxWidth())
                FolderRefreshNotice(interactions.folderRefreshError, model::retryFolderRefresh)
                interactions.favoriteFolderError?.let {
                    Text(glamourErrorMessage(it), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = model::retryFavoriteFolders, enabled = !busy) { Text(stringResource(R.string.glamour_retry)) }
                }
                if (!interactions.isLoadingFavoriteFolders && interactions.favoriteFolderError == null && interactions.favoriteFolders.isEmpty()) {
                    Text(stringResource(R.string.glamour_no_folders))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(interactions.favoriteFolders, key = { it.id }) { folder ->
                        FilterChip(
                            modifier = Modifier.fillMaxWidth(), enabled = !busy,
                            selected = interactions.selectedFavoriteFolderId == folder.id,
                            onClick = { model.selectFavoriteFolder(folder.id) },
                            label = { Column {
                                Text(folder.name)
                                FolderVisibility(folder)
                            } },
                        )
                    }
                }
                TextButton(enabled = !busy, onClick = { model.clearNotice(); isCreating = true }) {
                    Text(stringResource(R.string.glamour_new_folder))
                }
            }
        },
        confirmButton = { TextButton(
            enabled = !busy && !interactions.isLoadingFavoriteFolders && interactions.selectedFavoriteFolderId != null,
            onClick = { interactions.selectedFavoriteFolderId?.let(model::submitFavorite) },
        ) { Text(stringResource(R.string.glamour_save_favorite)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = model::closeFavoritePicker) { Text(stringResource(R.string.glamour_cancel)) } },
    )
    if (isCreating) GlamourFolderFormDialog(null, state.isCreatingFolder, state.folderMutationError,
        onSave = { name, public -> model.createFolder(name, public) },
        onDismiss = { isCreating = false })
}

@Composable
private fun GlamourFolderFormDialog(
    folder: GlamourFavoriteFolder?,
    saving: Boolean,
    error: String?,
    onSave: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(folder?.id) { mutableStateOf(folder?.name.orEmpty()) }
    var isPublic by rememberSaveable(folder?.id) { mutableStateOf(folder?.isPublic ?: true) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (folder == null) R.string.glamour_new_folder else R.string.glamour_edit_folder)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { if (it.length <= GlamourFolderNameMaximumLength) name = it },
                enabled = !saving, singleLine = true, label = { Text(stringResource(R.string.glamour_folder_name)) },
                supportingText = { Text(stringResource(R.string.glamour_folder_name_limit, GlamourFolderNameMaximumLength)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = stringResource(R.string.glamour_public_folder)
                Text(label, Modifier.weight(1f))
                Switch(checked = isPublic, enabled = !saving, onCheckedChange = { isPublic = it },
                    modifier = Modifier.testTag("glamour-folder-public").semantics { contentDescription = label })
            }
            Text(stringResource(R.string.glamour_folder_visibility_hint), style = MaterialTheme.typography.bodySmall)
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(glamourErrorMessage(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !saving && name.trim().isNotEmpty(), onClick = { onSave(name.trim(), isPublic) }) {
            Text(stringResource(R.string.glamour_save))
        } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.glamour_cancel)) } },
    )
}

@Composable
private fun FolderVisibility(folder: GlamourFavoriteFolder) {
    Text(stringResource(if (folder.isPublic) R.string.glamour_public else R.string.glamour_private), style = MaterialTheme.typography.labelSmall)
    if (folder.isDefault) Text(stringResource(R.string.glamour_default_folder), style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun FolderRefreshNotice(error: String?, onRetry: () -> Unit) {
    if (error != null) Column {
        Text(stringResource(R.string.glamour_saved_refresh_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.glamour_retry)) }
    }
}
