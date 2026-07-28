package top.cxmeow.risingstones.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import okhttp3.OkHttpClient
import top.cxmeow.risingstones.auth.webview.KeystoreRisingStonesCookieStore
import top.cxmeow.risingstones.auth.webview.AndroidRisingStonesWebCookieJar
import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState
import top.cxmeow.risingstones.core.auth.supports
import top.cxmeow.risingstones.feature.account.data.RisingStonesAccountApiService
import top.cxmeow.risingstones.feature.account.data.RisingStonesAccountSessionValidator
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel
import top.cxmeow.risingstones.feature.account.ui.compose.RisingStonesAccountScreen
import top.cxmeow.risingstones.feature.forum.data.OfficialForumApiService
import top.cxmeow.risingstones.feature.forum.ui.compose.RisingStonesForumScreen
import top.cxmeow.risingstones.feature.glamour.data.GlamourApiService
import top.cxmeow.risingstones.feature.glamour.data.RisingStonesGlamourSessionValidator
import top.cxmeow.risingstones.feature.glamour.ui.compose.RisingStonesGlamourScreen
import top.cxmeow.risingstones.feature.personaldata.data.PersonalDataApiService
import top.cxmeow.risingstones.feature.personaldata.data.RisingStonesPersonalDataSessionValidator
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataScreen
import top.cxmeow.risingstones.feature.recruitment.data.DutyRecruitmentApiService
import top.cxmeow.risingstones.feature.recruitment.ui.compose.RisingStonesRecruitmentScreen
import top.cxmeow.risingstones.network.OkHttpRisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesApiClient
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.ui.compose.RisingStonesWebLoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    RisingStonesApp()
                }
            }
        }
    }
}

@Composable
private fun RisingStonesApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val httpClient = remember { OkHttpClient() }
    val publicApiClient = remember(httpClient) {
        RisingStonesPublicApiClient(
            transport = OkHttpRisingStonesHttpClient(
                client = httpClient,
                defaultHeaders = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to "RisingStonesAndroid/${BuildConfig.VERSION_NAME}",
                ),
            ),
        )
    }
    val sessionProvider = remember(httpClient, publicApiClient) {
        RisingStonesWebCookieSessionProvider(
            store = KeystoreRisingStonesCookieStore(context),
            webCookieJar = AndroidRisingStonesWebCookieJar(),
            sessionValidator = RisingStonesPersonalDataSessionValidator(
                baseValidator = RisingStonesGlamourSessionValidator(
                    baseValidator = RisingStonesAccountSessionValidator(
                        baseValidator = RisingStonesApiClient(httpClient),
                        client = publicApiClient,
                    ),
                    client = publicApiClient,
                ),
                client = publicApiClient,
            ),
        )
    }
    val forumService = remember(sessionProvider, publicApiClient) {
        OfficialForumApiService(
            client = publicApiClient,
            sessionProvider = sessionProvider,
        )
    }
    val accountService = remember(sessionProvider, publicApiClient) {
        RisingStonesAccountApiService(
            client = publicApiClient,
            sessionProvider = sessionProvider,
        )
    }
    val recruitmentService = remember(sessionProvider, publicApiClient) {
        DutyRecruitmentApiService(
            client = publicApiClient,
            sessionProvider = sessionProvider,
        )
    }
    val glamourService = remember(sessionProvider, publicApiClient) {
        GlamourApiService(
            client = publicApiClient,
            sessionProvider = sessionProvider,
        )
    }
    val personalDataService = remember(sessionProvider, publicApiClient) {
        PersonalDataApiService(
            risingStonesClient = publicApiClient,
            sessionProvider = sessionProvider,
        )
    }
    val accountViewModel = viewModel<RisingStonesAccountViewModel>(
        factory = viewModelFactory {
            initializer {
                RisingStonesAccountViewModel(accountService)
            }
        },
    )
    val sessionState by sessionProvider.sessionState.collectAsStateWithLifecycle()
    var isShowingAccount by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingRecruitment by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingGlamour by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingPersonalData by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var isManagingSession by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(sessionProvider) {
        sessionProvider.restore()
    }
    LaunchedEffect(sessionState) {
        if (!sessionState.supports(RisingStonesCapability.GlamourAuthenticated)) {
            isShowingGlamour = false
        }
        if (!sessionState.supports(RisingStonesCapability.PersonalData)) {
            isShowingPersonalData = false
        }
    }
    if (isShowingAccount) {
        val activeSession = sessionState as? RisingStonesSessionState.Active
        if (isManagingSession || !sessionState.supports(RisingStonesCapability.AccountRead)) {
            RisingStonesWebLoginScreen(
                sessionProvider = sessionProvider,
                onNavigateBack = {
                    if (activeSession == null) {
                        isShowingAccount = false
                    } else {
                        isManagingSession = false
                    }
                },
            )
        } else {
            RisingStonesAccountScreen(
                viewModel = accountViewModel,
                sessionDisplayName = activeSession?.displayName,
                canDailySignIn = sessionState.supports(RisingStonesCapability.DailySignIn),
                onManageSession = { isManagingSession = true },
                onNavigateBack = { isShowingAccount = false },
            )
        }
    } else if (isShowingRecruitment) {
        RisingStonesRecruitmentScreen(
            service = recruitmentService,
            onNavigateBack = { isShowingRecruitment = false },
        )
    } else if (
        isShowingGlamour &&
        sessionState.supports(RisingStonesCapability.GlamourAuthenticated)
    ) {
        RisingStonesGlamourScreen(
            service = glamourService,
            onNavigateBack = { isShowingGlamour = false },
        )
    } else if (
        isShowingPersonalData &&
        sessionState.supports(RisingStonesCapability.PersonalData)
    ) {
        RisingStonesPersonalDataScreen(
            service = personalDataService,
            onNavigateBack = { isShowingPersonalData = false },
        )
    } else {
        RisingStonesForumScreen(
            service = forumService,
            onOpenAccount = { isShowingAccount = true },
            onOpenRecruitment = { isShowingRecruitment = true },
            onOpenGlamour = if (
                sessionState.supports(RisingStonesCapability.GlamourAuthenticated)
            ) {
                { isShowingGlamour = true }
            } else {
                null
            },
            onOpenPersonalData = if (
                sessionState.supports(RisingStonesCapability.PersonalData)
            ) {
                { isShowingPersonalData = true }
            } else {
                null
            },
        )
    }
}
