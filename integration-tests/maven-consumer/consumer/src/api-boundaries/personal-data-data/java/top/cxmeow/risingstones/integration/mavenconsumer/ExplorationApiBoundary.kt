package top.cxmeow.risingstones.integration.mavenconsumer

import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.personaldata.data.PersonalDataApiService
import top.cxmeow.risingstones.feature.personaldata.data.BundledPersonalDataCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

suspend fun readPublishedExploration(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): ExplorationOverview {
    val service: PersonalDataExplorationService = PersonalDataApiService(client, session)
    val legacy: PersonalDataService = service
    check(legacy.hasCommunityIdentity)
    return service.fetchExplorationOverview(ExplorationBoard.OccultCrescent)
}

suspend fun readPublishedOfficialCatalogs(): PersonalDataOfficialCatalogs {
    val provider: PersonalDataCatalogProvider = BundledPersonalDataCatalogProvider()
    return provider.fetchCatalogs()
}

suspend fun readPublishedDashboard(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): PersonalDataDashboardData {
    val catalogs: PersonalDataSupplementaryCatalogProvider = BundledPersonalDataCatalogProvider()
    check(catalogs.fetchSupplementaryCatalogs().oceanFish.isNotEmpty())
    val service: PersonalDataDashboardService = PersonalDataApiService(client, session, BundledPersonalDataCatalogProvider())
    service.raidImageUrl(1001)
    service.achievementIconUrl(1001)
    service.fetchSupplementaryCatalogs()
    return service.fetchDashboardSection(PersonalDataDashboardSectionKind.SavageRaids)
}

suspend fun readPublishedPersonalDataDetails(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): List<PersonalDataGlamourSetRecord> {
    val service: PersonalDataReadingService = PersonalDataApiService(client, session, BundledPersonalDataCatalogProvider())
    val fishing: List<PersonalDataFishingRank> = service.fetchFishingRanking(PersonalDataFishingRankingKind.Fish)
    val races: List<PersonalDataRaceUsage> = service.fetchRaceUsage()
    service.itemIconUrl(1001)
    check(fishing.all { it.count >= 0 } && races.all { it.days == null || it.days!! >= 0 })
    return service.fetchGlamourSetRecords()
}

suspend fun readPublishedFrontline(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): Pair<PersonalDataFrontlineCatalogs, List<PersonalDataFrontlineData>> {
    val service: PersonalDataFrontlineService = PersonalDataApiService(client, session, BundledPersonalDataCatalogProvider())
    val legacy: PersonalDataService = service
    check(legacy.hasCommunityIdentity)
    service.frontlineJobIconUrl("骑士")
    service.frontlineJobIconUrl("骑士", hollow = false)
    service.frontlineCompanyFlagUrl("恒辉队")
    service.frontlineAchievementImageUrl()
    val catalogs: PersonalDataFrontlineCatalogs = service.fetchFrontlineCatalogs()
    return catalogs to PersonalDataFrontlineSection.entries.map { service.fetchFrontlineSection(it) }
}

fun countPublishedFrontlineRows(data: PersonalDataFrontlineData): Int = when (data) {
    is PersonalDataFrontlineData.Overview -> data.rows.size
    is PersonalDataFrontlineData.Weekly -> data.rows.size
    is PersonalDataFrontlineData.Jobs -> data.rows.size
    is PersonalDataFrontlineData.Best -> data.rows.size
    is PersonalDataFrontlineData.Maps -> data.rows.size
    is PersonalDataFrontlineData.MapJobs -> data.rows.size
    is PersonalDataFrontlineData.Achievements -> data.rows.size
}

suspend fun readPublishedUltimate(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): Pair<List<PersonalDataUltimateRecord>, List<PersonalDataUltimateData>> {
    val service: PersonalDataUltimateService = PersonalDataApiService(client, session)
    val legacy: PersonalDataService = service
    check(legacy.hasCommunityIdentity)
    service.ultimateCoverUrl(968)
    service.ultimateJobIconUrl("骑士")
    service.ultimateJobOrder("骑士")
    service.ultimateMedalImageUrl(968)
    return service.fetchUltimateRecords() to PersonalDataUltimateSection.entries.map { service.fetchUltimateSection(968, it) }
}

fun countPublishedUltimateRows(data: PersonalDataUltimateData): Int = when (data) {
    is PersonalDataUltimateData.Party -> data.rows.size
    is PersonalDataUltimateData.Jobs -> data.rows.size
    is PersonalDataUltimateData.Partners -> data.rows.size
    is PersonalDataUltimateData.Phases -> data.rows.size
    is PersonalDataUltimateData.Deaths -> data.rows.size
}

fun publishedUltimateFirstClearDate(record: PersonalDataUltimateRecord): String? = when (val time = record.firstClearAt) {
    is UltimateRecordTime.CalendarDate -> time.value.toString()
    is UltimateRecordTime.LocalTime -> time.value.toString()
    is UltimateRecordTime.OffsetTime -> time.value.toString()
    null -> null
}

suspend fun readPublishedPhantomWeapons(
    client: RisingStonesPublicApiClient,
    session: RisingStonesSessionProvider,
): Pair<PhantomWeaponExplorationSnapshot, PhantomWeaponCatalog> {
    val service: PersonalDataPhantomWeaponService = PersonalDataApiService(client, session)
    service.phantomWeaponItemIconUrl(30694)
    service.phantomWeaponElementIconUrl(PhantomWeaponElement.Yellow)
    service.phantomWeaponLensImageUrl(1)
    return service.fetchPhantomWeaponExploration() to service.fetchPhantomWeaponCatalog()
}

suspend fun readPublishedPhantomCatalog(): PhantomWeaponCatalog =
    top.cxmeow.risingstones.feature.personaldata.data.BundledPhantomWeaponCatalogProvider().fetchPhantomWeaponCatalog()


suspend fun readPublishedShareCatalog(client: RisingStonesPublicApiClient, session: RisingStonesSessionProvider): PersonalDataShareCatalogs {
    val service: PersonalDataShareResourceService = PersonalDataApiService(client, session)
    service.sharePageUrl(PersonalDataShareKind.Fishing)
    service.shareImageUrl(PersonalDataShareImage.PhantomJobIcon(82271))
    return top.cxmeow.risingstones.feature.personaldata.data.BundledPersonalDataShareCatalogProvider().fetchShareCatalogs()
}
