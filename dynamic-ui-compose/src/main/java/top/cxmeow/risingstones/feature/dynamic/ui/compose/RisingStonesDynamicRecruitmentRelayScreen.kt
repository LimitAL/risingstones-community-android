package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicRecruitmentRelayViewModel

/** Confirms only visibility, matching the official recruitment sharing flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesDynamicRecruitmentRelayScreen(
    actionService: DynamicActionService,
    relayService: DynamicRecruitmentRelayService,
    recruitmentId: Int,
    origin: DynamicOrigin,
    sourceTitle: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPublished: () -> Unit = {},
) {
    val model: DynamicRecruitmentRelayViewModel = viewModel(
        key = "dynamic-recruitment-relay-${System.identityHashCode(actionService)}-$origin-$recruitmentId",
        factory = remember(actionService, relayService, recruitmentId, origin, sourceTitle) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>) = DynamicRecruitmentRelayViewModel(
                    actionService, relayService, recruitmentId, origin, sourceTitle,
                ) as T
            }
        },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val back = { if (model.requestClose()) onNavigateBack() }
    BackHandler(onBack = back)
    LaunchedEffect(model) { model.initialize() }
    SideEffect(model::synchronizeAccess)
    LaunchedEffect(state.completedId) {
        state.completedId?.let { if (model.takePublishedResult(it)) {
            onPublished()
            onNavigateBack()
        } }
    }
    val editable = state.usable && !state.isSubmitting && state.completedId == null
    Scaffold(modifier.testTag("dynamic-recruitment-relay-screen"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.dynamic_recruitment_relay_title)) },
            navigationIcon = { TextButton(onClick = back, modifier = Modifier.testTag("dynamic-recruitment-relay-back")) {
                Text(stringResource(R.string.dynamic_back))
            } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.usable) {
                    Text(state.sourceTitle.orEmpty(), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("dynamic-recruitment-relay-source"))
                    Text(stringResource(R.string.dynamic_recruitment_relay_description))
                    Text(stringResource(R.string.dynamic_publish_visibility), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DynamicVisibility.entries.forEach { visibility ->
                            FilterChip(selected = state.visibility == visibility, enabled = editable,
                                onClick = { model.setVisibility(visibility) },
                                modifier = Modifier.testTag("dynamic-recruitment-relay-visibility-${visibility.name}"),
                                label = { Text(stringResource(visibility.label())) })
                        }
                    }
                }
                if (state.isSubmitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("dynamic-recruitment-relay-error")) }
                Button(onClick = model::submit, enabled = editable,
                    modifier = Modifier.testTag("dynamic-recruitment-relay-submit")) {
                    Text(stringResource(R.string.dynamic_recruitment_relay_confirm))
                }
            }
        }
    }
}
