package top.cxmeow.risingstones.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import top.cxmeow.risingstones.auth.webview.AndroidRisingStonesWebCookieJar
import top.cxmeow.risingstones.auth.webview.KeystoreRisingStonesCookieStore
import top.cxmeow.risingstones.auth.webview.RisingStonesWebCookieSessionProvider
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState
import top.cxmeow.risingstones.core.auth.supports
import top.cxmeow.risingstones.feature.account.data.RisingStonesAccountApiService
import top.cxmeow.risingstones.feature.recruitment.data.RisingStonesRecruitmentSessionValidator
import top.cxmeow.risingstones.feature.account.data.RisingStonesAccountSessionValidator
import top.cxmeow.risingstones.feature.account.presentation.RisingStonesAccountViewModel
import top.cxmeow.risingstones.feature.account.ui.compose.RisingStonesAccountScreen
import top.cxmeow.risingstones.feature.account.ui.compose.RisingStonesAccountGuildNavigation
import top.cxmeow.risingstones.feature.guild.data.GuildApiService
import top.cxmeow.risingstones.feature.guild.data.GuildImageUploadApiService
import top.cxmeow.risingstones.feature.guild.data.RisingStonesGuildSessionValidator
import top.cxmeow.risingstones.feature.dynamic.data.DynamicApiService
import top.cxmeow.risingstones.feature.dynamic.data.RisingStonesDynamicSessionValidator
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel
import top.cxmeow.risingstones.feature.dynamic.ui.compose.RisingStonesDynamicScreen
import top.cxmeow.risingstones.feature.forum.data.OfficialForumApiService
import top.cxmeow.risingstones.feature.forum.ui.compose.RisingStonesForumScreen
import top.cxmeow.risingstones.feature.glamour.data.GlamourApiService
import top.cxmeow.risingstones.feature.glamour.data.RisingStonesGlamourSessionValidator
import top.cxmeow.risingstones.feature.glamour.ui.compose.RisingStonesGlamourScreen
import top.cxmeow.risingstones.feature.personaldata.data.PersonalDataApiService
import top.cxmeow.risingstones.feature.personaldata.data.BundledPersonalDataCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.data.RisingStonesPersonalDataSessionValidator
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataScreen
import top.cxmeow.risingstones.feature.personaldata.ui.compose.LocalPersonalDataShareHost
import top.cxmeow.risingstones.feature.recruitment.data.DutyRecruitmentApiService
import top.cxmeow.risingstones.feature.recruitment.ui.compose.RisingStonesRecruitmentScreen
import top.cxmeow.risingstones.network.OkHttpRisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesApiClient
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.ui.compose.RisingStonesWebLoginScreen

import androidx.compose.ui.platform.LocalUriHandler
import top.cxmeow.risingstones.feature.message.data.MessageApiService
import top.cxmeow.risingstones.feature.message.data.RisingStonesMessageSessionValidator
import top.cxmeow.risingstones.feature.message.presentation.MessageViewModel
import top.cxmeow.risingstones.feature.message.ui.compose.RisingStonesMessageScreen

import top.cxmeow.risingstones.feature.profile.data.ProfileApiService

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
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val runtime = viewModel<RisingStonesRuntime>(factory = viewModelFactory {
        initializer { RisingStonesRuntime(context) }
    })
    val sessionProvider = runtime.sessionProvider
    val forumService = runtime.forumService
    val accountService = runtime.accountService
    val recruitmentService = runtime.recruitmentService
    val glamourService = runtime.glamourService
    val personalDataService = runtime.personalDataService
    val dynamicViewModel = viewModel<DynamicViewModel>(factory = viewModelFactory {
        initializer { DynamicViewModel(runtime.dynamicService) }
    })
    val messageViewModel = viewModel<MessageViewModel>(factory = viewModelFactory {
        initializer { MessageViewModel(runtime.messageService) }
    })
    val readingNavigation = runtime.readingNavigation
    val uriHandler = LocalUriHandler.current
    var isShowingMessages by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingDynamic by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val sessionState by sessionProvider.sessionState.collectAsStateWithLifecycle()
    val protectedStateRevision by runtime.authenticatedViewModels.revision.collectAsStateWithLifecycle()
    var isShowingAccount by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingRecruitment by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingGlamour by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isShowingPersonalData by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var isManagingSession by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(sessionState) {
        if (!sessionState.supports(RisingStonesCapability.MessageRead)) {
            isShowingMessages = false
            messageViewModel.clearProtectedContent()
        }
        if (!sessionState.supports(RisingStonesCapability.DynamicRead)) {
            isShowingDynamic = false
            dynamicViewModel.clearProtectedContent()
        }
        if (!sessionState.supports(RisingStonesCapability.GlamourAuthenticated)) {
            isShowingGlamour = false
        }
        if (!sessionState.supports(RisingStonesCapability.PersonalData)) {
            isShowingPersonalData = false
        }
    }
    val readingAccess = CommunityReadingAccess(
        profile = sessionState.supports(RisingStonesCapability.AccountRead),
        dynamic = sessionState.supports(RisingStonesCapability.DynamicRead),
        glamour = sessionState.supports(RisingStonesCapability.GlamourAuthenticated),
        guildRecruitment = recruitmentService.hasCommunityIdentity,
        guild = sessionState.supports(RisingStonesCapability.GuildRead),
    )
    val readingServices = androidx.compose.runtime.remember(runtime) {
        CommunityReadingServices(runtime.profileService, forumService, runtime.dynamicService, glamourService,
            recruitmentService, runtime.guildService, guildImages = runtime.guildImageUploadService)
    }
    CommunityReadingHost(readingNavigation, readingAccess, readingServices) {
        if (isShowingDynamic && sessionState.supports(RisingStonesCapability.DynamicRead)) {
            RisingStonesDynamicScreen(
                viewModel = dynamicViewModel,
                onNavigateBack = { isShowingDynamic = false },
                canOpenReference = { it.destination()?.let(readingAccess::allows) == true },
                onOpenReference = { it.destination()?.let(readingNavigation::open) },
            )
        } else if (isShowingMessages && sessionState.supports(RisingStonesCapability.MessageRead)) {
            RisingStonesMessageScreen(messageViewModel, { isShowingMessages = false },
                canOpenTarget = { it.destination()?.let(readingAccess::allows) == true },
                onOpenTarget = { it.destination()?.let(readingNavigation::open) },
                onOpenOfficialLink = uriHandler::openUri,
            )
        } else if (isShowingAccount) {
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
                val accountViewModel = viewModel<RisingStonesAccountViewModel>(
                    viewModelStoreOwner = runtime.authenticatedViewModels,
                    factory = viewModelFactory {
                        initializer { RisingStonesAccountViewModel(accountService) }
                    },
                )
                RisingStonesAccountGuildNavigation(if (readingAccess.guild) {
                    { readingNavigation.open(CommunityDestination.Guild) }
                } else null) {
                    RisingStonesAccountScreen(
                        viewModel = accountViewModel,
                        sessionDisplayName = activeSession?.displayName,
                        canDailySignIn = sessionState.supports(RisingStonesCapability.DailySignIn),
                        onManageSession = { isManagingSession = true },
                        onNavigateBack = { isShowingAccount = false },
                    )
                }
            }
        } else if (isShowingRecruitment) {
            key(protectedStateRevision) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides runtime.authenticatedViewModels) {
                    RisingStonesRecruitmentScreen(
                        service = recruitmentService,
                        onNavigateBack = { isShowingRecruitment = false },
                    )
                }
            }
        } else if (
            isShowingGlamour &&
            sessionState.supports(RisingStonesCapability.GlamourAuthenticated)
        ) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides runtime.authenticatedViewModels) {
                RisingStonesGlamourScreen(
                    service = glamourService,
                    onNavigateBack = { isShowingGlamour = false },
                )
            }
        } else if (
            isShowingPersonalData &&
            sessionState.supports(RisingStonesCapability.PersonalData)
        ) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides runtime.authenticatedViewModels,
                LocalPersonalDataShareHost provides runtime.personalDataShareHost) {
                RisingStonesPersonalDataScreen(
                    service = personalDataService,
                    onNavigateBack = { isShowingPersonalData = false },
                )
            }
        } else {
            key(protectedStateRevision) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides runtime.authenticatedViewModels) {
                    RisingStonesForumScreen(
                        service = forumService,
                        onOpenProfile = if (sessionState.supports(RisingStonesCapability.AccountRead)) {
                            { readingNavigation.open(CommunityDestination.Profile(top.cxmeow.risingstones.feature.profile.domain.ProfileOwner.Self)) }
                        } else null,
                        onOpenMessages = if (sessionState.supports(RisingStonesCapability.MessageRead)) {
                            { isShowingMessages = true }
                        } else null,
                        onOpenDynamic = if (sessionState.supports(RisingStonesCapability.DynamicRead)) {
                            { isShowingDynamic = true }
                        } else null,
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
        }
    }
}


private class RisingStonesRuntime(context: Context) : ViewModel() {
    val authenticatedViewModels = SessionFeatureViewModelStoreOwner()
    val readingNavigation = CommunityReadingNavigation()
    private val httpClient = OkHttpClient()
    private val publicApiClient = RisingStonesPublicApiClient(
        transport = OkHttpRisingStonesHttpClient(
            client = httpClient,
            defaultHeaders = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "RisingStonesAndroid/${BuildConfig.VERSION_NAME}",
            ),
        ),
    )
    val sessionProvider = RisingStonesWebCookieSessionProvider(
        store = KeystoreRisingStonesCookieStore(context),
        webCookieJar = AndroidRisingStonesWebCookieJar(),
        sessionValidator = RisingStonesGuildSessionValidator(
            client = publicApiClient,
            baseValidator = RisingStonesMessageSessionValidator(
                client = publicApiClient,
                baseValidator = RisingStonesDynamicSessionValidator(
                    client = publicApiClient,
                    baseValidator = RisingStonesPersonalDataSessionValidator(
                        client = publicApiClient,
                        baseValidator = RisingStonesGlamourSessionValidator(
                            client = publicApiClient,
                            baseValidator = RisingStonesAccountSessionValidator(
                                client = publicApiClient,
                                baseValidator = RisingStonesRecruitmentSessionValidator(
                                    client = publicApiClient,
                                    baseValidator = RisingStonesApiClient(httpClient),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
    val profileService = ProfileApiService(publicApiClient, sessionProvider)
    val guildService = GuildApiService(publicApiClient, sessionProvider)
    val guildImageUploadService = GuildImageUploadApiService(publicApiClient, sessionProvider)
    val messageService = MessageApiService(publicApiClient, sessionProvider)
    val forumService = OfficialForumApiService(publicApiClient, sessionProvider)
    val accountService = RisingStonesAccountApiService(publicApiClient, sessionProvider)
    val recruitmentService = DutyRecruitmentApiService(publicApiClient, sessionProvider)
    val glamourService = GlamourApiService(publicApiClient, sessionProvider)
    val personalDataService = PersonalDataApiService(publicApiClient, sessionProvider, BundledPersonalDataCatalogProvider())
    val dynamicService = DynamicApiService(publicApiClient, sessionProvider)
    val personalDataShareHost = PersonalDataPngShareHost(context, { sessionProvider.sessionState.value.supports(RisingStonesCapability.PersonalData) })

    init {
        viewModelScope.launch {
            combine(sessionProvider.sessionState, sessionProvider.credentialRevision) { state, revision ->
                state to revision
            }.collect { (state, credentialRevision) ->
                val previousRevision = authenticatedViewModels.revision.value
                authenticatedViewModels.synchronize(state, credentialRevision)
                if (previousRevision != authenticatedViewModels.revision.value || !state.supports(RisingStonesCapability.PersonalData)) personalDataShareHost.clear()
                readingNavigation.synchronize(CommunityReadingAccess(
                    profile = state.supports(RisingStonesCapability.AccountRead),
                    dynamic = state.supports(RisingStonesCapability.DynamicRead),
                    glamour = state.supports(RisingStonesCapability.GlamourAuthenticated),
                    guildRecruitment = recruitmentService.hasCommunityIdentity,
                    guild = state.supports(RisingStonesCapability.GuildRead),
                ), authenticatedViewModels.revision.value)
            }
        }
        viewModelScope.launch { sessionProvider.restore() }
    }

    override fun onCleared() {
        personalDataShareHost.clear()
        authenticatedViewModels.viewModelStore.clear()
        readingNavigation.clear()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }
}
