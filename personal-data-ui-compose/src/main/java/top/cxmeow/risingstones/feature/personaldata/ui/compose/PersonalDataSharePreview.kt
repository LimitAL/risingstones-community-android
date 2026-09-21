package top.cxmeow.risingstones.feature.personaldata.ui.compose

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareArtifact
import top.cxmeow.risingstones.feature.personaldata.presentation.*

/** Optional platform destination. Implementations should retain only an application context. */
interface PersonalDataShareHost {
    suspend fun share(artifact: PersonalDataShareArtifact)
    fun clear()
}
val LocalPersonalDataShareHost = staticCompositionLocalOf<PersonalDataShareHost?> { null }
internal val LocalPersonalDataShareAction = staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalDataSharePreview(state: PersonalDataShareUiState, close: () -> Unit,
    share: (() -> Unit)?, modifier: Modifier = Modifier) {
    BackHandler(onBack = close)
    val preview = state as? PersonalDataShareUiState.Preview
    var showText by rememberSaveable { mutableStateOf(false) }
    Scaffold(modifier.testTag("personal-data-share-preview"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.pds_preview)) }, navigationIcon = {
            TextButton(onClick = close, Modifier.testTag("share-preview-close")) { Text(stringResource(R.string.personal_data_back)) }
        }, actions = {
            if (preview != null && share != null) TextButton(onClick = share, enabled = !preview.isSharing,
                modifier = Modifier.testTag("share-preview-send")) { Text(stringResource(R.string.pds_share)) }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 750.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    PersonalDataShareUiState.Closed -> Unit
                    PersonalDataShareUiState.Rendering -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.pds_rendering)) }
                    PersonalDataShareUiState.AuthenticationRequired -> Text(stringResource(R.string.personal_data_authentication_failed))
                    is PersonalDataShareUiState.NotReady -> Text(stringResource(R.string.pds_not_ready))
                    PersonalDataShareUiState.Failed -> Text(stringResource(R.string.pds_failed))
                    is PersonalDataShareUiState.Preview -> {
                        if (state.artifact.missingImageCount > 0) Text(stringResource(R.string.pds_assets_missing))
                        if (state.shareFailed) Text(stringResource(R.string.pds_send_failed), color = MaterialTheme.colorScheme.error)
                        if (state.isSharing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { showText = !showText }, Modifier.testTag("share-preview-read")) { Text(stringResource(R.string.pds_content)) }
                        if (showText) Text(state.artifact.accessibleText, Modifier.testTag("share-preview-text"))
                        val bitmap = remember(state.artifact) { state.artifact.pngBytes().let { BitmapFactory.decodeByteArray(it,0,it.size) } }
                        // The retained artifact owns bytes; this decoded bitmap belongs only to this composition.
                        DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                        bitmap?.let { Image(it.asImageBitmap(), state.artifact.accessibleText,
                            Modifier.fillMaxWidth().aspectRatio(state.artifact.width.toFloat()/state.artifact.height).testTag("share-preview-image"),
                            contentScale = ContentScale.FillWidth) }
                    }
                }
            }
        }
    }
}
