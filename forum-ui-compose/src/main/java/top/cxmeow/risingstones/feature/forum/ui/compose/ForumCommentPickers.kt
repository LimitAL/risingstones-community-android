package top.cxmeow.risingstones.feature.forum.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumCommentEmojiNumbers
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumCommentEditorState
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumDetailViewModel
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumInteractionError
import top.cxmeow.risingstones.feature.forum.presentation.OfficialForumLoadStatus

@Composable
internal fun ForumCommentEmojiPicker(model: OfficialForumDetailViewModel) {
    val loader = LocalForumCommentEmojiLoader.current
    LazyVerticalGrid(columns = GridCells.Adaptive(56.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("forum-emoji-picker"),
        contentPadding = PaddingValues(4.dp)) {
        items(OfficialForumCommentEmojiNumbers.toList(), key = { it }) { number ->
            val label = stringResource(R.string.forum_comment_emoji_number, number)
            val bitmap by produceState<android.graphics.Bitmap?>(null, number, loader) { value = loader(number) }
            TextButton(onClick = { model.insertCommentEmoji(number); model.closeCommentEmojiPicker() },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("forum-emoji-$number").semantics { contentDescription = label },
                contentPadding = PaddingValues(8.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(32.dp)) }
                    ?: Text(number.toString())
            }
        }
    }
}

@Composable
internal fun ForumCommentMentionPicker(state: OfficialForumCommentEditorState, model: OfficialForumDetailViewModel) {
    val query = state.candidateQuery.trim()
    val filtered = state.candidates.filter { it.name.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxWidth().testTag("forum-mention-picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.forum_mention_following_only), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(state.candidateQuery, model::updateCommentMentionQuery,
            label = { Text(stringResource(R.string.forum_mention_filter)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("forum-mention-query"))
        if (state.candidateStatus == OfficialForumLoadStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.candidateError?.let { error ->
            Text(stringResource(if (error == OfficialForumInteractionError.AuthenticationRequired) R.string.forum_auth_required
                else R.string.forum_mention_load_failed), color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = model::refreshCommentMentionCandidates,
            enabled = state.candidateStatus != OfficialForumLoadStatus.Loading,
            modifier = Modifier.testTag("forum-refresh-mentions")) {
            Text(stringResource(if (state.candidateError != null) R.string.forum_retry else R.string.forum_refresh))
        }
        if (filtered.isEmpty() && state.candidateStatus == OfficialForumLoadStatus.Loaded && state.candidateError == null) {
            Text(stringResource(if (state.candidates.isEmpty()) R.string.forum_mention_empty else R.string.forum_mention_no_match))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { "${it.uuid.length}:${it.uuid}:${it.name}" }) { candidate ->
                TextButton(onClick = { model.selectCommentMention(candidate); model.closeCommentMentionPicker() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("forum-mention-${candidate.uuid}")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        candidate.avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                            RisingStonesRemoteImage(url, null, Modifier.size(36.dp), fallback = {})
                        }
                        Column(Modifier.weight(1f)) {
                            Text(candidate.name)
                            val location = listOf(candidate.areaName, candidate.groupName).filter(String::isNotBlank).joinToString(" / ")
                            if (location.isNotEmpty()) Text(location, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
