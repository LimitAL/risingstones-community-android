package top.cxmeow.risingstones.ui.compose

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.auth.webview.RisingStonesWebLoginController
import top.cxmeow.risingstones.auth.webview.RisingStonesWebLoginState
import top.cxmeow.risingstones.auth.webview.RisingStonesCredentialRejectionClassifier
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

/**
 * Optional default UI. Host apps may use [RisingStonesWebLoginController] directly and provide a
 * completely different UI stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RisingStonesWebLoginScreen(
    sessionProvider: RisingStonesWebCookieSessionProvider,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
) {
    val sessionState by sessionProvider.sessionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isShowingLogin by remember { mutableStateOf(false) }
    var webState by remember { mutableStateOf(RisingStonesWebLoginState()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var blockedNavigation by remember { mutableStateOf<String?>(null) }
    RisingStonesLoginCaptureProtection(enabled = isShowingLogin)
    val controller = remember(sessionProvider) {
        RisingStonesWebLoginController(
            context = context,
            onCredentialCandidate = { candidate ->
                loginError = null
                scope.launch {
                    sessionProvider.accept(candidate)
                        .onSuccess {
                            loginError = null
                            webView?.stopLoading()
                            webView?.destroy()
                            webView = null
                            isShowingLogin = false
                        }
                        .onFailure { error ->
                            if (!RisingStonesCredentialRejectionClassifier.Default.isRejected(error)) {
                                loginError = error.message
                            }
                        }
                }
            },
            onStateChange = { webState = it },
            onBlockedNavigation = { blockedNavigation = it },
        )
    }

    BackHandler(enabled = isShowingLogin) {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        isShowingLogin = false
    }
    BackHandler(enabled = !isShowingLogin && onNavigateBack != null) {
        onNavigateBack?.invoke()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isShowingLogin) {
                            stringResource(R.string.rising_stones_login_title)
                        } else {
                            stringResource(R.string.rising_stones_app_title)
                        },
                    )
                },
                navigationIcon = {
                    if (isShowingLogin) {
                        TextButton(
                            onClick = {
                                webView?.stopLoading()
                                webView?.destroy()
                                webView = null
                                isShowingLogin = false
                            },
                        ) {
                            Text(stringResource(R.string.rising_stones_cancel))
                        }
                    } else if (onNavigateBack != null) {
                        TextButton(onClick = onNavigateBack) {
                            Text(stringResource(R.string.rising_stones_back))
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        if (isShowingLogin) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                AndroidView(
                    factory = {
                        controller.createWebView().also { created -> webView = created }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (
                    webState.isLoading ||
                    sessionState == RisingStonesSessionState.Validating
                ) {
                    CircularProgressIndicator(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
                webState.currentHost?.let { host ->
                    Text(
                        text = stringResource(R.string.rising_stones_current_host, host),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                val validationError = loginError
                if (blockedNavigation != null || !validationError.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (!validationError.isNullOrBlank()) {
                                validationError
                            } else {
                                stringResource(R.string.rising_stones_blocked_navigation)
                            },
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!validationError.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    loginError = null
                                    webView?.let(controller::retryCredentialInspection)
                                },
                            ) {
                                Text(stringResource(R.string.rising_stones_retry_validation))
                            }
                        }
                    }
                }
            }
        } else {
            SessionContent(
                sessionState = sessionState,
                loginError = loginError,
                onLogin = {
                    blockedNavigation = null
                    loginError = null
                    isShowingLogin = true
                },
                onSignOut = {
                    scope.launch {
                        sessionProvider.signOut()
                            .onFailure { loginError = it.message }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }
}

@Composable
internal fun RisingStonesLoginCaptureProtection(enabled: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        val wasAlreadySecure = window
            ?.attributes
            ?.flags
            ?.and(WindowManager.LayoutParams.FLAG_SECURE)
            ?.let { it != 0 }
            ?: false
        if (enabled && !wasAlreadySecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (enabled && !wasAlreadySecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun SessionContent(
    sessionState: RisingStonesSessionState,
    loginError: String?,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.rising_stones_login_description),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.rising_stones_credential_handling),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
        when (sessionState) {
            RisingStonesSessionState.Restoring,
            RisingStonesSessionState.Validating,
            -> CircularProgressIndicator()

            is RisingStonesSessionState.Active -> {
                Text(
                    text = sessionState.displayName?.let {
                        stringResource(R.string.rising_stones_signed_in_as, it)
                    } ?: stringResource(R.string.rising_stones_signed_in),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onLogin) {
                        Text(stringResource(R.string.rising_stones_relogin))
                    }
                    TextButton(onClick = onSignOut) {
                        Text(stringResource(R.string.rising_stones_sign_out))
                    }
                }
                Spacer(Modifier.height(24.dp))
                SessionCapabilitySummary(sessionState)
            }

            is RisingStonesSessionState.Invalid,
            RisingStonesSessionState.SignedOut,
            -> Button(onClick = onLogin) {
                Text(stringResource(R.string.rising_stones_login_action))
            }
        }
        val message = loginError
            ?: (sessionState as? RisingStonesSessionState.Invalid)?.reason
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal val displayedCapabilities = listOf(
    RisingStonesCapability.AccountRead,
    RisingStonesCapability.DailySignIn,
    RisingStonesCapability.ForumWrite,
    RisingStonesCapability.ForumImageUpload,
    RisingStonesCapability.RecruitmentAuthenticated,
    RisingStonesCapability.RecruitmentWrite,
    RisingStonesCapability.GlamourAuthenticated,
    RisingStonesCapability.PersonalData,
    RisingStonesCapability.DynamicRead,
    RisingStonesCapability.MessageRead,
    RisingStonesCapability.GuildRead,
    RisingStonesCapability.GuildWrite,
    RisingStonesCapability.GuildImageUpload,
)

@Composable
internal fun SessionCapabilitySummary(sessionState: RisingStonesSessionState.Active) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.rising_stones_capabilities_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.rising_stones_capabilities_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        displayedCapabilities.forEach { capability ->
            val isVerified = capability in sessionState.capabilities
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rising-stones-capability-${capability.name}")
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = capability.displayName(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (isVerified) {
                        stringResource(R.string.rising_stones_capability_verified)
                    } else {
                        stringResource(R.string.rising_stones_capability_not_verified)
                    },
                    color = if (isVerified) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        var hasCopiedReport by remember(sessionState.capabilities) {
            mutableStateOf(false)
        }
        val context = LocalContext.current
        val capabilityReportLabel =
            stringResource(R.string.rising_stones_capabilities_title)
        TextButton(
            onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(
                        ClipData.newPlainText(
                            capabilityReportLabel,
                            sanitizedCapabilityReport(sessionState),
                        ),
                    )
                hasCopiedReport = true
            },
            modifier = Modifier
                .align(Alignment.End)
                .testTag("rising-stones-copy-capability-report"),
        ) {
            Text(
                stringResource(
                    if (hasCopiedReport) {
                        R.string.rising_stones_capability_report_copied
                    } else {
                        R.string.rising_stones_copy_capability_report
                    },
                ),
            )
        }
    }
}

internal fun sanitizedCapabilityReport(
    sessionState: RisingStonesSessionState.Active,
): String = displayedCapabilities.joinToString(separator = "\n") { capability ->
    "${capability.name}=${capability in sessionState.capabilities}"
}

@Composable
private fun RisingStonesCapability.displayName(): String = stringResource(
    when (this) {
        RisingStonesCapability.AccountRead -> R.string.rising_stones_capability_account_read
        RisingStonesCapability.DailySignIn -> R.string.rising_stones_capability_daily_sign_in
        RisingStonesCapability.ForumWrite -> R.string.rising_stones_capability_forum_write
        RisingStonesCapability.ForumImageUpload ->
            R.string.rising_stones_capability_forum_image_upload
        RisingStonesCapability.RecruitmentAuthenticated ->
            R.string.rising_stones_capability_recruitment_authenticated
        RisingStonesCapability.RecruitmentWrite ->
            R.string.rising_stones_capability_recruitment_write
        RisingStonesCapability.GlamourAuthenticated ->
            R.string.rising_stones_capability_glamour_authenticated
        RisingStonesCapability.PersonalData -> R.string.rising_stones_capability_personal_data
        RisingStonesCapability.MessageRead -> R.string.rising_stones_capability_message_read
        RisingStonesCapability.DynamicRead -> R.string.rising_stones_capability_dynamic_read
        RisingStonesCapability.GuildRead -> R.string.rising_stones_capability_guild_read
        RisingStonesCapability.GuildWrite -> R.string.rising_stones_capability_guild_write
        RisingStonesCapability.GuildImageUpload -> R.string.rising_stones_capability_guild_image_upload
    },
)
