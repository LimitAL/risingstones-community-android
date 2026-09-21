package top.cxmeow.risingstones.feature.recruitment.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecruitmentInteractionPanels(state: DutyRecruitmentUiState,
    interaction: RecruitmentInteractionState, model: DutyRecruitmentViewModel) {
    val authors by model.authorState.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val repliesScrollState = key(interaction.selectedReviewId) { rememberLazyListState() }
    if (interaction.isResponseComposerOpen && model.canInteract) {
        AlertDialog(
            modifier = Modifier.widthIn(max = 640.dp).imePadding(),
            onDismissRequest = { if (!state.isResponding) model.closeResponseComposer() },
            title = { Text(stringResource(R.string.recruitment_respond)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.recruitment_contact_explanation))
                    OutlinedTextField(interaction.contactDraft, model::updateResponseDraft,
                        enabled = !state.isResponding,
                        label = { Text(stringResource(R.string.recruitment_your_contact)) },
                        modifier = Modifier.fillMaxWidth().testTag("recruitment-response-input"))
                    if (!model.canRespond && !state.isResponding) {
                        Text(stringResource(R.string.recruitment_draft_response_unavailable))
                    } else interaction.responseError?.let { RecruitmentInteractionErrorText(it) }
                    if (state.isResponding) LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = model::discardResponseDraft, enabled = !state.isResponding,
                        modifier = Modifier.testTag("recruitment-discard-response")) {
                        Text(stringResource(R.string.recruitment_discard_draft))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = model::submitResponseDraft,
                    enabled = !state.isResponding && model.canRespond && interaction.contactDraft.isNotBlank(),
                    modifier = Modifier.testTag("recruitment-submit-response")) {
                    Text(stringResource(R.string.recruitment_send_response))
                }
            },
            dismissButton = {
                TextButton(onClick = model::closeResponseComposer, enabled = !state.isResponding) {
                    Text(stringResource(R.string.recruitment_keep_draft))
                }
            },
        )
    }
    if (lifecycleState.isAtLeast(Lifecycle.State.STARTED)) interaction.selectedReviewId?.let { rootId ->
        val root = state.reviews.firstOrNull { it.id == rootId }
        ModalBottomSheet(onDismissRequest = model::dismissReviewReplies) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.recruitment_replies), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = model::dismissReviewReplies) { Text(stringResource(R.string.recruitment_close)) }
                }
                root?.let { Text(it.content, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }
                LazyColumn(Modifier.fillMaxWidth().testTag("recruitment-replies"),
                    state = repliesScrollState,
                    contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.subcomments[rootId].orEmpty(), key = RolePlayRecruitmentSubcomment::id) { reply ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                RecruitmentAuthorName(reply.authorName, authors.subcommentAuthors[rootId]?.get(reply.id))
                                reply.replyTargetName?.let { Text(stringResource(R.string.recruitment_reply_to, it),
                                    style = MaterialTheme.typography.labelSmall) }
                                Text(reply.content)
                                reply.imageUrls.forEach { RecruitmentContentImage(it) }
                            }
                        }
                    }
                    item {
                        interaction.subcommentErrors[rootId]?.let {
                            RecruitmentInteractionErrorText(it)
                            TextButton(onClick = {
                                if (rootId in interaction.subcommentErrorIsPaginationIds) model.loadMoreSubcomments(rootId)
                                else root?.let(model::loadSubcomments)
                            }) {
                                Text(stringResource(R.string.recruitment_retry))
                            }
                        }
                        if (rootId in state.loadingSubcommentIds) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (rootId in interaction.hasMoreSubcommentIds) {
                            TextButton(onClick = { model.loadMoreSubcomments(rootId) },
                                modifier = Modifier.testTag("recruitment-more-replies")) {
                                Text(stringResource(R.string.recruitment_more))
                            }
                        } else if (state.subcomments[rootId].isNullOrEmpty() && rootId !in interaction.subcommentErrors) {
                            Text(stringResource(R.string.recruitment_no_replies))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RecruitmentResponseActions(state: DutyRecruitmentUiState, interaction: RecruitmentInteractionState,
    model: DutyRecruitmentViewModel) {
    val supported = state.board == RecruitmentBoardKind.Duty && state.dutyDetail != null ||
        state.board == RecruitmentBoardKind.Beginner && state.communityDetail?.summary?.beginner != null
    if (!supported) return
    val responded = state.dutyDetail?.isResponded == true || state.communityDetail?.summary?.beginner?.isResponded == true
    if (responded) {
        Text(stringResource(R.string.recruitment_responded), style = MaterialTheme.typography.titleSmall)
        val contact = state.responseContactInfo?.takeIf(String::isNotBlank) ?: if (state.notice == RecruitmentNotice.ResponseSubmitted && !interaction.hasRefreshedResponseContact) null
            else (state.dutyDetail?.contactInfo ?: state.communityDetail?.summary?.beginner?.recruiterContactInfo)?.takeIf(String::isNotBlank)
        if (contact != null) RecruitmentContactInfo(contact)
        else Text(stringResource(R.string.recruitment_contact_unavailable), style = MaterialTheme.typography.bodySmall)
    } else if (model.canRespond) {
        Button(onClick = model::openResponseComposer, enabled = !state.isResponding,
            modifier = Modifier.testTag("recruitment-respond")) { Text(stringResource(R.string.recruitment_respond)) }
    } else if (model.canInteract && interaction.isCurrentUserAuthor == null) {
        Text(stringResource(R.string.recruitment_response_eligibility_unknown), style = MaterialTheme.typography.bodySmall)
    }
    if (!model.canRespond && model.canInteract && interaction.contactDraft.isNotEmpty()) {
        TextButton(onClick = model::resumeResponseComposer, enabled = !state.isResponding,
            modifier = Modifier.testTag("recruitment-resume-response")) {
            Text(stringResource(R.string.recruitment_resume_draft))
        }
    }
}

@Composable
private fun RecruitmentContactInfo(contact: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.recruitment_recruiter_contact), style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.text.selection.SelectionContainer { Text(contact) }
    }
}

@Composable
internal fun RecruitmentRolePlayContent(state: DutyRecruitmentUiState, interaction: RecruitmentInteractionState,
    model: DutyRecruitmentViewModel) {
    val browsing by model.browsingState.collectAsStateWithLifecycle()
    val authors by model.authorState.collectAsStateWithLifecycle()
    Text(stringResource(R.string.recruitment_roleplay_extras), fontWeight = FontWeight.SemiBold)
    state.rating?.let { Text(stringResource(R.string.recruitment_rating, it.averageScore, it.totalCount)) }
    if (state.isLoadingRating) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.ratingError != null) RecruitmentReadRetry(model::refreshRating)
    if (state.members.isNotEmpty()) Text(state.members.joinToString(" · ") { it.name })
    if (state.isLoadingMembers) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.membersError != null) RecruitmentReadRetry(model::refreshMembers)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.recruitment_reviews), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = model::refreshReviews, enabled = !state.isLoadingReviews) {
            Text(stringResource(R.string.recruitment_refresh))
        }
    }
    if (model.canBrowseExtended) RecruitmentChoice(R.string.recruitment_review_order,
        browsing.reviewOrder.name, RolePlayRecruitmentReviewOrder.entries.map { order -> order.name to stringResource(when (order) {
            RolePlayRecruitmentReviewOrder.Latest -> R.string.recruitment_latest
            RolePlayRecruitmentReviewOrder.Hottest -> R.string.recruitment_hottest
            RolePlayRecruitmentReviewOrder.ScoreDescending -> R.string.recruitment_highest_score
            RolePlayRecruitmentReviewOrder.ScoreAscending -> R.string.recruitment_lowest_score
        }) }, "review-order", allowAll = false) { name ->
        model.setReviewOrder(RolePlayRecruitmentReviewOrder.entries.firstOrNull { it.name == name }
            ?: RolePlayRecruitmentReviewOrder.Latest)
    }
    if (state.isLoadingReviews) LinearProgressIndicator(Modifier.fillMaxWidth())
    interaction.interactionError?.let { RecruitmentInteractionErrorText(it) }
    state.reviews.forEach { review ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecruitmentAuthorName(review.authorName, authors.reviewAuthors[review.id])
                review.score?.let { Text(stringResource(R.string.recruitment_review_score, it), style = MaterialTheme.typography.labelSmall) }
                Text(review.content)
                review.imageUrls.forEach { RecruitmentContentImage(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.likeReview(review) },
                        enabled = model.canInteract && review.id !in state.likingReviewIds,
                        modifier = Modifier.testTag("recruitment-like-review-${review.id}")) {
                        Text(stringResource(if (review.isLiked) R.string.recruitment_unlike_review else R.string.recruitment_like_review,
                            review.likeCount))
                    }
                    if (review.childCount > 0) TextButton(onClick = { model.openReviewReplies(review) },
                        modifier = Modifier.testTag("recruitment-review-replies-${review.id}")) {
                        Text(stringResource(R.string.recruitment_view_replies, review.childCount))
                    }
                }
                review.childPreviewReplies.forEach { reply ->
                    RecruitmentAuthorName(reply.authorName, authors.subcommentAuthors[review.id]?.get(reply.id))
                    Text(reply.content, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
    if (state.reviewsError != null) RecruitmentReadRetry {
        if (interaction.reviewsErrorIsPagination) model.loadMoreReviews() else model.refreshReviews()
    }
    if (state.isLoadingMoreReviews) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (state.hasMoreReviews) TextButton(onClick = model::loadMoreReviews,
        modifier = Modifier.testTag("recruitment-more-reviews")) { Text(stringResource(R.string.recruitment_more)) }
    else if (state.reviews.isEmpty() && !state.isLoadingReviews && state.reviewsError == null) {
        Text(stringResource(R.string.recruitment_no_reviews))
    }
}

@Composable
internal fun RecruitmentReadRetry(onRetry: () -> Unit) {
    Text(stringResource(R.string.recruitment_load_failed), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.recruitment_retry)) }
}

@Composable
internal fun RecruitmentInteractionErrorText(error: RecruitmentInteractionError) {
    Text(stringResource(when (error) {
        RecruitmentInteractionError.Unavailable -> R.string.recruitment_write_unavailable
        RecruitmentInteractionError.AuthenticationRequired -> R.string.recruitment_auth_required
        RecruitmentInteractionError.InvalidInput -> R.string.recruitment_invalid_input
        RecruitmentInteractionError.Failed -> R.string.recruitment_action_failed
    }), color = MaterialTheme.colorScheme.error)
}
