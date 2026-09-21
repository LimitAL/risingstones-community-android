package top.cxmeow.risingstones.integration.mavenconsumer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.Clock
import java.time.ZoneId
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlineBestKind
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriodKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataReadingPage
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataReadingUiState
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataReadingViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataReadingViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataSetFilter
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataSetSort
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataReadingScreen
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataDashboardPane
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataDashboardViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataDashboardViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataDashboardFishGroup
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineUiState
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineWeeklyMetric
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataFrontlinePane
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateService
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUltimateUiState
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUltimateViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUltimateViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataUltimatePane

fun publishedDashboardFactory(service: PersonalDataService) = PersonalDataDashboardViewModelFactory(service)

@Composable
fun PublishedPersonalDataDashboard(model: PersonalDataDashboardViewModel, modifier: Modifier = Modifier) {
    RisingStonesPersonalDataDashboardPane(model, model::close, {}, modifier,
        itemIconUrl = { null }, achievementIconUrl = { null }, raidImageUrl = { null })
}

fun configurePublishedDashboard(model: PersonalDataDashboardViewModel) {
    model.open(PersonalDataBoard.Fishing)
    model.selectFishGroup(PersonalDataDashboardFishGroup.Ocean)
    model.setIncludeUnobtained(true)
    model.showMore()
    model.retryCatalogs()
}

fun publishedReadingFactory(service: PersonalDataService) = PersonalDataReadingViewModelFactory(service)

fun configurePublishedReading(model: PersonalDataReadingViewModel): PersonalDataReadingUiState {
    model.open(PersonalDataReadingPage.Sets)
    model.setSetFilter(PersonalDataSetFilter.Recorded)
    model.setSetSort(PersonalDataSetSort.NewestFirst)
    model.showMore()
    return model.state.value
}

@Composable
fun PublishedPersonalDataReading(model: PersonalDataReadingViewModel, modifier: Modifier = Modifier) {
    RisingStonesPersonalDataReadingScreen(model, model::close, modifier, itemIconUrl = { null })
}

fun publishedFrontlineFactory(service: PersonalDataService) = PersonalDataFrontlineViewModelFactory(service)

fun publishedFrontlineViewModel(service: PersonalDataService, clock: Clock): PersonalDataFrontlineViewModel =
    PersonalDataFrontlineViewModel(service, clock)

fun createPublishedFrontlineViewModel(service: PersonalDataService, clock: Clock): PersonalDataFrontlineViewModel =
    PersonalDataFrontlineViewModelFactory(service, clock).create(PersonalDataFrontlineViewModel::class.java)

fun configurePublishedFrontline(model: PersonalDataFrontlineViewModel): PersonalDataFrontlineUiState {
    model.open()
    model.selectSection(PersonalDataFrontlineSection.Jobs)
    model.selectOverallPeriod(FrontlinePeriodKind.Since51)
    model.selectJobPeriod(FrontlinePeriodKind.Last30Days)
    model.selectJob("骑士")
    model.selectWeeklyMetric(PersonalDataFrontlineWeeklyMetric.WinRate)
    model.selectBestKind(FrontlineBestKind.Healing)
    model.selectMap("昂萨哈凯尔")
    model.selectMapJob(null)
    model.setIncludeUnobtained(true)
    model.updateQuery("成就")
    model.showMore()
    model.retrySection(PersonalDataFrontlineSection.MapJobs)
    model.retryCatalogs()
    model.refresh()
    return model.state.value
}

fun releasePublishedFrontline(model: PersonalDataFrontlineViewModel) {
    model.close()
    model.clearProtectedContent()
}

@Composable
fun PublishedPersonalDataFrontline(
    model: PersonalDataFrontlineViewModel,
    service: PersonalDataFrontlineService,
    modifier: Modifier = Modifier,
) {
    RisingStonesPersonalDataFrontlinePane(
        viewModel = model,
        onNavigateBack = model::close,
        modifier = modifier,
        showBack = false,
        jobIconUrl = service::frontlineJobIconUrl,
        companyFlagUrl = service::frontlineCompanyFlagUrl,
        achievementImageUrl = service::frontlineAchievementImageUrl,
    )
}

fun publishedUltimateFactory(service: PersonalDataService) = PersonalDataUltimateViewModelFactory(service)

fun publishedUltimateViewModel(service: PersonalDataService, zone: ZoneId): PersonalDataUltimateViewModel =
    PersonalDataUltimateViewModel(service, zone)

fun createPublishedUltimateViewModel(service: PersonalDataService, zone: ZoneId): PersonalDataUltimateViewModel =
    PersonalDataUltimateViewModelFactory(service, zone).create(PersonalDataUltimateViewModel::class.java)

fun configurePublishedUltimate(model: PersonalDataUltimateViewModel): PersonalDataUltimateUiState {
    model.open()
    model.selectEncounter(968)
    model.selectSection(PersonalDataUltimateSection.Deaths)
    model.showMoreDeaths()
    model.showMorePartners()
    model.retryOverview()
    model.retrySection(PersonalDataUltimateSection.Partners)
    model.refresh()
    return model.state.value
}

fun releasePublishedUltimate(model: PersonalDataUltimateViewModel) {
    model.clearEncounterSelection()
    model.close()
    model.clearProtectedContent()
}

@Composable
fun PublishedPersonalDataUltimate(
    model: PersonalDataUltimateViewModel,
    service: PersonalDataUltimateService,
    modifier: Modifier = Modifier,
) {
    RisingStonesPersonalDataUltimatePane(
        viewModel = model,
        onNavigateBack = model::close,
        modifier = modifier,
        showBack = false,
        coverUrl = service::ultimateCoverUrl,
        jobIconUrl = service::ultimateJobIconUrl,
        medalImageUrl = service::ultimateMedalImageUrl,
    )
}

fun configurePublishedPhantomWeapons(model: top.cxmeow.risingstones.feature.personaldata.presentation.ExplorationViewModel):
    top.cxmeow.risingstones.feature.personaldata.presentation.PhantomWeaponUiState {
    model.openPhantomWeapons()
    model.selectPhantomWeaponStage(top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponStage.Penumbrae)
    model.setPhantomWeaponQuery("半影")
    model.setPhantomWeaponsObtainedOnly(true)
    model.setPhantomWeaponsExpanded(true)
    model.openPhantomWeaponHistory()
    model.returnToPhantomWeapons()
    model.retryPhantomWeaponCatalog()
    model.phantomWeaponItemIconUrl(30694)
    model.phantomWeaponElementIconUrl(top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponElement.Yellow)
    model.phantomWeaponLensImageUrl(1)
    model.closePhantomWeapons()
    return model.phantomWeapons.value
}


fun buildPublishedShare(input: top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataShareInput,
    catalogs: top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareCatalogs) =
    top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataShareBuilder.build(input, catalogs)

fun publishedShareRenderer(context: android.content.Context,
    resources: top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareResourceService):
    top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareRenderer =
    top.cxmeow.risingstones.feature.personaldata.ui.compose.AndroidPersonalDataShareRenderer(context, resources)

@Composable
fun PublishedPersonalDataWithShare(service: PersonalDataService,
    host: top.cxmeow.risingstones.feature.personaldata.ui.compose.PersonalDataShareHost) {
    androidx.compose.runtime.CompositionLocalProvider(
        top.cxmeow.risingstones.feature.personaldata.ui.compose.LocalPersonalDataShareHost provides host,
    ) { top.cxmeow.risingstones.feature.personaldata.ui.compose.RisingStonesPersonalDataScreen(service, {}) }
}
