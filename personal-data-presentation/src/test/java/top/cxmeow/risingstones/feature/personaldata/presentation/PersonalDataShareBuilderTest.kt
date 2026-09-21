package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

class PersonalDataShareBuilderTest {
    private val identity = PersonalDataIdentity("Character", "Area", "World", null)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun fishingUsesTwoNewestRowsAndDoesNotTreatFailedOldAchievementAsKnown() {
        val older = PersonalDataFishCatch("Older", Instant.parse("2026-01-01T00:00:00Z"), 1)
        val newest = PersonalDataFishCatch("Newest", Instant.parse("2026-03-01T00:00:00Z"), 1)
        val unknown = PersonalDataFishCatch("Unknown", null, 1)
        val stale = PersonalDataAchievementRecord(9, "Stale", null, Instant.parse("2026-04-01T00:00:00Z"))
        val state = dashboardState(
            sections = mapOf(
                PersonalDataDashboardSectionKind.FishingSummary to loaded(PersonalDataDashboardData.FishingSummary(
                    FishingOverview(20, .5, 3, 100))),
                PersonalDataDashboardSectionKind.BigFish to loaded(PersonalDataDashboardData.BigFish(listOf(older, unknown, newest))),
                PersonalDataDashboardSectionKind.FishingAchievements to PersonalDataDashboardSectionState(
                    status = PersonalDataDashboardLoadStatus.Failed,
                    data = PersonalDataDashboardData.FishingAchievements(listOf(stale)),
                    failure = PersonalDataDashboardFailure.LoadFailed,
                ),
            ),
            catalogs = PersonalDataOfficialCatalogs(fish = mapOf(1 to FishKingCatalogEntry(1, 101, "Newest", "1"))),
            supplementary = PersonalDataSupplementaryCatalogs(fishingAchievements = listOf(
                PersonalDataAchievementCatalogEntry(9, "Stale", null, 99))),
        )

        val content = readyContent(PersonalDataShareBuilder.build(
            PersonalDataShareInput.Dashboard(PersonalDataShareKind.Fishing, identity, state))) as FishingShareContent
        assertEquals(listOf("Newest", "Older"), content.recentBigFish.map { it.record.name })
        assertEquals(101, content.recentBigFish.first().iconId)
        assertSame(PersonalDataShareOptional.Failed, content.latestAchievement)
        assertEquals(listOf(older, unknown, newest),
            (state.sections.getValue(PersonalDataDashboardSectionKind.BigFish).data as PersonalDataDashboardData.BigFish).rows)
    }

    @Test fun fishingRequiresMoreThanOneBigFishAndRevokedStateWinsOverStaleData() {
        val row = PersonalDataFishCatch("Only", Instant.EPOCH, 1)
        val loadedState = dashboardState(mapOf(
            PersonalDataDashboardSectionKind.FishingSummary to loaded(PersonalDataDashboardData.FishingSummary(FishingOverview(1, 1.0, 0, 0))),
            PersonalDataDashboardSectionKind.BigFish to loaded(PersonalDataDashboardData.BigFish(listOf(row))),
        ))
        assertNotReady(PersonalDataShareBuilder.build(PersonalDataShareInput.Dashboard(
            PersonalDataShareKind.Fishing, identity, loadedState)), PersonalDataShareNotReadyReason.InsufficientData)

        val revoked = loadedState.copy(sections = loadedState.sections + (
            PersonalDataDashboardSectionKind.FishingSummary to PersonalDataDashboardSectionState(
                PersonalDataDashboardLoadStatus.AuthRequired,
                PersonalDataDashboardData.FishingSummary(FishingOverview(999, 1.0, 0, 0)),
                PersonalDataDashboardFailure.AuthenticationRequired,
            )))
        assertNotReady(PersonalDataShareBuilder.build(PersonalDataShareInput.Dashboard(
            PersonalDataShareKind.Fishing, identity, revoked)), PersonalDataShareNotReadyReason.AuthenticationRequired)
    }

    @Test fun glamourKeepsDuplicateSetRecordsAndUsesAllPeriodsForFourRepresentatives() {
        val set1 = GlamourCatalogSet(1, "Set 1", 101, listOf(
            GlamourCatalogSetItem(1, 11, "A", 1), GlamourCatalogSetItem(2, 12, "B", 2)))
        val set2 = GlamourCatalogSet(2, "Set 2", 102, listOf(GlamourCatalogSetItem(1, 21, "C", 3)))
        val time1 = Instant.parse("2026-01-01T00:00:00Z")
        val time2 = Instant.parse("2026-02-01T00:00:00Z")
        val setRows = listOf(
            PersonalDataGlamourSetRecord("a", 1, setOf(11, 12), time1),
            PersonalDataGlamourSetRecord("b", 1, setOf(11, 12), time2),
            PersonalDataGlamourSetRecord("c", 1, setOf(11, 12), null),
        )
        val categories = listOf(category(1, 1), category(2, 3), category(3, 4))
        val vanity = listOf(
            vanity(1, 2, PersonalDataVanityPeriod.AllTime, "All-time weapon"),
            vanity(1, 9, PersonalDataVanityPeriod.LastYear, "Year weapon"),
            vanity(2, 8, PersonalDataVanityPeriod.Unknown, "Gear"),
            vanity(3, 7, PersonalDataVanityPeriod.LastYear, "Jewelry"),
        )
        val state = dashboardState(
            sections = mapOf(
                PersonalDataDashboardSectionKind.GlamourSummary to loaded(PersonalDataDashboardData.GlamourSummary(GlamourOverview(1, 2, 3))),
                PersonalDataDashboardSectionKind.Sets to loaded(PersonalDataDashboardData.Sets(setRows)),
                PersonalDataDashboardSectionKind.Vanity to loaded(PersonalDataDashboardData.Vanity(vanity)),
                PersonalDataDashboardSectionKind.Accessories to loaded(PersonalDataDashboardData.Accessories(listOf(
                    PersonalDataAccessoryUsage(7, 100, 2), PersonalDataAccessoryUsage(8, 1, 1)))),
            ),
            catalogs = PersonalDataOfficialCatalogs(glamour = GlamourCatalogSummary(2, 2, 0,
                listOf(set1, set2), listOf(GlamourCatalogFashionAccessory(7, 70, "Seven"),
                    GlamourCatalogFashionAccessory(8, 80, "Eight")))),
            supplementary = PersonalDataSupplementaryCatalogs(vanityCategories = categories),
        )

        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Dashboard(
            PersonalDataShareKind.Glamour, identity, state))) as GlamourShareContent
        assertEquals(150.0, content.setCollectionRatePercent!!, 0.0)
        assertEquals(listOf("b", "a", "c"), content.latestSets.map { it.record.key })
        assertEquals("Year weapon", known<GlamourShareVanityFavorite>(content.favorites.weapon).record.name)
        assertEquals("Gear", known<GlamourShareVanityFavorite>(content.favorites.gear).record.name)
        assertEquals("Jewelry", known<GlamourShareVanityFavorite>(content.favorites.jewelry).record.name)
        assertEquals(8, known<GlamourShareAccessoryFavorite>(content.favorites.fashionAccessory).record.accessoryId)
    }

    @Test fun glamourUnknownMembershipAndUnknownTopCountStayUnknownInsteadOfBecomingZero() {
        val set = GlamourCatalogSet(1, "Set", null, listOf(GlamourCatalogSetItem(1, 11, "A", 1)))
        val state = dashboardState(
            sections = mapOf(
                PersonalDataDashboardSectionKind.GlamourSummary to loaded(PersonalDataDashboardData.GlamourSummary(GlamourOverview(null, null, null))),
                PersonalDataDashboardSectionKind.Sets to loaded(PersonalDataDashboardData.Sets(listOf(
                    PersonalDataGlamourSetRecord("bad", 1, emptySet(), null, hasInvalidItemIds = true)))),
                PersonalDataDashboardSectionKind.Vanity to loaded(PersonalDataDashboardData.Vanity(listOf(vanity(1, null)))),
                PersonalDataDashboardSectionKind.Accessories to loaded(PersonalDataDashboardData.Accessories(emptyList())),
            ),
            catalogs = PersonalDataOfficialCatalogs(glamour = GlamourCatalogSummary(1, 0, 0, listOf(set))),
            supplementary = PersonalDataSupplementaryCatalogs(vanityCategories = listOf(category(1, 1))),
        )
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Dashboard(
            PersonalDataShareKind.Glamour, identity, state))) as GlamourShareContent
        assertNull(content.setCollectionRatePercent)
        assertSame(PersonalDataShareOptional.Failed, content.favorites.weapon)
        assertSame(PersonalDataShareOptional.Empty, content.favorites.fashionAccessory)
    }

    @Test fun savageRetainsEverySeriesTierAndRaidIncludingUnclearedEntries() {
        val series = (1..7).map { number -> SavageRaidCatalogSeries("Series $number", "S$number", listOf(
            SavageRaidCatalogTier("Tier $number", "层 $number", false, null, listOf(
                SavageRaidCatalogEntry(number * 10, "Raid A", number),
                SavageRaidCatalogEntry(number * 10 + 1, "Raid B", number + 100),
            )))) }
        val older = PersonalDataSavageClear(10, Instant.parse("2026-01-01T00:00:00Z"), false, null, null)
        val newer = PersonalDataSavageClear(21, Instant.parse("2026-02-01T00:00:00Z"), true, null, null)
        val state = dashboardState(
            sections = mapOf(
                PersonalDataDashboardSectionKind.SavageSummary to loaded(PersonalDataDashboardData.SavageSummary(SavageOverview(2, 3, 4, 5.0))),
                PersonalDataDashboardSectionKind.SavageRaids to loaded(PersonalDataDashboardData.SavageRaids(listOf(older, newer))),
            ),
            catalogs = PersonalDataOfficialCatalogs(savageSeries = series),
            supplementary = null,
        )
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Dashboard(
            PersonalDataShareKind.Savage, identity, state))) as SavageShareContent
        assertEquals(7, content.series.size)
        assertEquals(14, content.series.flatMap { it.tiers }.flatMap { it.raids }.size)
        assertTrue(content.series.last().tiers.single().raids.all { it.records.isEmpty() })
        assertSame(newer, content.latestClear)
    }

    @Test fun savageRequiresAtLeastOneClearRecord() {
        val series = (1..7).map { number -> SavageRaidCatalogSeries("Series $number", "S$number", listOf(
            SavageRaidCatalogTier("Tier $number", "层 $number", false, null, listOf(
                SavageRaidCatalogEntry(number, "Raid $number", number),
            )))) }
        val state = dashboardState(
            sections = mapOf(
                PersonalDataDashboardSectionKind.SavageSummary to loaded(PersonalDataDashboardData.SavageSummary(
                    SavageOverview(0, 0, 0, null))),
                PersonalDataDashboardSectionKind.SavageRaids to loaded(PersonalDataDashboardData.SavageRaids(emptyList())),
            ),
            catalogs = PersonalDataOfficialCatalogs(savageSeries = series),
            supplementary = null,
        )
        assertNotReady(PersonalDataShareBuilder.build(PersonalDataShareInput.Dashboard(
            PersonalDataShareKind.Savage, identity, state)), PersonalDataShareNotReadyReason.InsufficientData)
    }

    @Test fun frontlineUsesV51RadarAndRawGlobalTopFiveWithoutPeriodFilterOrDedupe() {
        val total = frontlineOverview(FrontlinePeriodKind.Total, FrontlineRanks(null, null, null, null, null, null))
        val v51 = frontlineOverview(FrontlinePeriodKind.Since51, FrontlineRanks(-1.0, 50.0, 80.0, 101.0, 0.0, null))
        val jobs = listOf(
            frontlineJob("A", 5, FrontlinePeriodKind.Total), frontlineJob("A", 9, FrontlinePeriodKind.Last30Days),
            frontlineJob("B", 8, null), frontlineJob("C", 7, FrontlinePeriodKind.Since51),
            frontlineJob("D", 6, FrontlinePeriodKind.Total), frontlineJob("E", 4, FrontlinePeriodKind.Total),
            frontlineJob("F", 3, FrontlinePeriodKind.Total),
        )
        val kill = best(FrontlineBestKind.Kills)
        val state = PersonalDataFrontlineUiState(
            sections = mapOf(
                PersonalDataFrontlineSection.Overview to frontlineLoaded(PersonalDataFrontlineData.Overview(listOf(total, v51))),
                PersonalDataFrontlineSection.Jobs to frontlineLoaded(PersonalDataFrontlineData.Jobs(jobs)),
                PersonalDataFrontlineSection.Best to frontlineLoaded(PersonalDataFrontlineData.Best(listOf(kill))),
                PersonalDataFrontlineSection.Achievements to PersonalDataFrontlineSectionState(
                    PersonalDataFrontlineLoadStatus.Failed,
                    PersonalDataFrontlineData.Achievements(listOf(FrontlineAchievementRecord(1, "Stale", null,
                        FrontlineDayStamp.OffsetTime(Instant.MAX)))),
                    PersonalDataFrontlineFailure.LoadFailed,
                ),
            ), zone = zone,
        )
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Frontline(identity, state))) as FrontlineShareContent
        assertSame(total, content.overall)
        assertNull(content.radar.kills)
        assertEquals(50.0, content.radar.healing!!, 0.0)
        assertNull(content.radar.damage)
        assertEquals(listOf("A", "B", "C", "D", "A"), content.commonJobs.map { it.jobName })
        assertSame(PersonalDataShareOptional.Failed, content.latestAchievement)
        assertSame(PersonalDataShareOptional.Empty, content.bestAssists)
    }

    @Test fun frontlineLatestAchievementUsesExplicitZoneAndKeepsOriginalStampType() {
        val total = frontlineOverview(FrontlinePeriodKind.Total, emptyRanks())
        val v51 = frontlineOverview(FrontlinePeriodKind.Since51, emptyRanks())
        val calendar = FrontlineAchievementRecord(1, "Calendar", null,
            FrontlineDayStamp.CalendarDate(LocalDate.of(2026, 9, 20)))
        val offset = FrontlineAchievementRecord(2, "Offset", null,
            FrontlineDayStamp.OffsetTime(Instant.parse("2026-09-19T17:00:00Z")))
        val state = PersonalDataFrontlineUiState(sections = mapOf(
            PersonalDataFrontlineSection.Overview to frontlineLoaded(PersonalDataFrontlineData.Overview(listOf(total, v51))),
            PersonalDataFrontlineSection.Jobs to frontlineLoaded(PersonalDataFrontlineData.Jobs(emptyList())),
            PersonalDataFrontlineSection.Best to frontlineLoaded(PersonalDataFrontlineData.Best(listOf(best(FrontlineBestKind.Kills)))),
            PersonalDataFrontlineSection.Achievements to frontlineLoaded(PersonalDataFrontlineData.Achievements(listOf(calendar, offset))),
        ), zone = zone)
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Frontline(identity, state))) as FrontlineShareContent
        val latest = known<FrontlineShareAchievement>(content.latestAchievement).record
        assertSame(offset, latest)
        assertTrue(latest.obtainedAt is FrontlineDayStamp.OffsetTime)
    }

    @Test fun ultimateKeepsFixedProgressOrderDuplicatesAndUnknownMetadataWithTypedTimes() {
        val unknownTime = PersonalDataUltimateRecord(733, 1, null, "Job", null, null, null)
        val local = PersonalDataUltimateRecord(968, 1, null, "Job",
            UltimateRecordTime.LocalTime(LocalDateTime.of(2026, 9, 20, 12, 0)), null, null)
        val offset = PersonalDataUltimateRecord(968, 2, null, "Job",
            UltimateRecordTime.OffsetTime(Instant.parse("2026-09-20T05:00:00Z")), null, null)
        val state = PersonalDataUltimateUiState(overviewStatus = PersonalDataUltimateLoadStatus.Loaded,
            records = listOf(unknownTime, local, offset), zone = zone)
        val catalogs = PersonalDataShareCatalogs(ultimateAchievements = listOf(
            UltimateShareAchievement(968, 3074, 4, "Known", "Detail")))
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Ultimate(identity, state), catalogs)) as UltimateShareContent
        assertEquals(listOf(733, 777, 887, 968, 1122, 1238, 1363), content.progress.map { it.territoryType })
        assertEquals(listOf(offset, local, unknownTime), content.timeline.map { it.record })
        assertEquals(3, content.timeline.size)
        assertNull(content.timeline.last().achievement)
        assertEquals(968, content.headerTerritoryType)
        assertNull(content.singleClearMedalId)
        assertTrue(content.timeline[1].record.firstClearAt is UltimateRecordTime.LocalTime)
    }

    @Test fun ultimateSingleRecordUsesMedalButMissingCatalogDoesNotInventOne() {
        val record = PersonalDataUltimateRecord(733, 1, null, "Job",
            UltimateRecordTime.OffsetTime(Instant.EPOCH), null, null)
        val state = PersonalDataUltimateUiState(overviewStatus = PersonalDataUltimateLoadStatus.Loaded,
            records = listOf(record), zone = zone)
        val missing = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Ultimate(identity, state))) as UltimateShareContent
        assertNull(missing.singleClearMedalId)
        val known = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Ultimate(identity, state),
            PersonalDataShareCatalogs(ultimateAchievements = listOf(UltimateShareAchievement(733, 1993, 1, "Name", "Detail"))))) as UltimateShareContent
        assertEquals(1, known.singleClearMedalId)
    }

    @Test fun occultBuildsTwentyFourJobsExactTreasureAndRecentWeaponFromOnlyFirstTwoStages() {
        val jobs = phantomJobs()
        val jobRecords = jobs.map { job -> explorationRecord("job-${job.id}",
            ExplorationField(ExplorationFieldKind.PhantomJob, job.id.toString()),
            ExplorationField(ExplorationFieldKind.Level, if (job.id == 0) "8" else job.levelCap.toString())) }
        val treasure = listOf(
            treasure("幸福兔", "copper", "2"), treasure("撒娇罐", "copper", "3"),
            treasure("幸福兔", "silver", "5"), treasure("撒娇罐", "gold", "10"),
            treasure("其他", "gold", "999"),
        )
        val overview = occultOverview(jobRecords, ExplorationSection(ExplorationSectionKind.TreasureChests, treasure))
        val definitions = firstTwoWeaponDefinitions() + PhantomWeaponDefinition(PhantomWeaponStage.Obscurum, 999,
            "Later", 999)
        val chosen = definitions.first { it.stage == PhantomWeaponStage.Umbrae }
        val records = listOf(
            PhantomWeaponItemRecord(chosen.itemId, null, 1,
                PhantomWeaponRecordTime.CalendarDate(LocalDate.of(2026, 9, 20)), chosen.name),
            PhantomWeaponItemRecord(999, null, 1,
                PhantomWeaponRecordTime.OffsetTime(Instant.parse("2030-01-01T00:00:00Z")), "Later"),
        )
        val weaponState = PhantomWeaponUiState(selectedStage = PhantomWeaponStage.Obscurum, items = records,
            catalog = PhantomWeaponCatalog(definitions, emptyList(), emptyList()), zone = zone)
        val catalogs = PersonalDataShareCatalogs(phantomJobs = jobs, weaponItemCategories = mapOf(
            chosen.itemId to PhantomShareWeaponCategory(2, "单手剑")))
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Occult(identity,
            ExplorationUiState(ExplorationBoard.OccultCrescent, overview = overview), weaponState), catalogs)) as OccultShareContent
        assertEquals((0..23).toList(), content.supportJobs.map { it.catalog.id })
        assertEquals(8, content.supportJobs.first().level)
        val treasureShare = known<OccultShareTreasure>(content.treasure)
        assertEquals(20, treasureShare.total)
        assertEquals(5, treasureShare.bronze)
        assertEquals(.25, treasureShare.bronzeFraction!!, 0.0)
        assertEquals(.5, treasureShare.goldFraction!!, 0.0)
        val weapon = known<OccultShareWeapon>(content.recentWeapon)
        assertEquals(chosen.itemId, weapon.definition.itemId)
        assertEquals("单手剑", weapon.category?.name)
        assertNotEquals(999, weapon.definition.itemId)
    }

    @Test fun occultOptionalFailuresDoNotReuseStaleRowsAndZeroTreasureHasUnknownFractions() {
        val jobs = phantomJobs()
        val jobRecords = jobs.map { explorationRecord("j${it.id}",
            ExplorationField(ExplorationFieldKind.PhantomJob, it.id.toString()),
            ExplorationField(ExplorationFieldKind.Level, "0")) }
        val failedTreasure = ExplorationSection(ExplorationSectionKind.TreasureChests,
            records = listOf(treasure("幸福兔", "gold", "999")), failure = ExplorationFailure.Network)
        val failedContent = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Occult(identity,
            ExplorationUiState(ExplorationBoard.OccultCrescent, overview = occultOverview(jobRecords, failedTreasure)),
            PhantomWeaponUiState(items = emptyList(), itemError = ExplorationError.Network)),
            PersonalDataShareCatalogs(phantomJobs = jobs))) as OccultShareContent
        assertSame(PersonalDataShareOptional.Failed, failedContent.treasure)
        assertSame(PersonalDataShareOptional.Failed, failedContent.recentWeapon)

        val zeroSection = ExplorationSection(ExplorationSectionKind.TreasureChests,
            listOf(treasure("幸福兔", "copper", "0")))
        val definitions = firstTwoWeaponDefinitions()
        val zeroContent = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Occult(identity,
            ExplorationUiState(ExplorationBoard.OccultCrescent, overview = occultOverview(jobRecords, zeroSection)),
            PhantomWeaponUiState(items = emptyList(), catalog = PhantomWeaponCatalog(definitions, emptyList(), emptyList()))),
            PersonalDataShareCatalogs(phantomJobs = jobs))) as OccultShareContent
        val value = known<OccultShareTreasure>(zeroContent.treasure)
        assertEquals(0, value.total)
        assertNull(value.bronzeFraction)
        assertNull(value.silverFraction)
        assertNull(value.goldFraction)
        assertSame(PersonalDataShareOptional.Empty, zeroContent.recentWeapon)
    }

    @Test fun occultOverflowAndUnknownGradeFailInsteadOfWrappingOrBecomingGold() {
        val jobs = phantomJobs()
        val jobRecords = jobs.map { explorationRecord("j${it.id}",
            ExplorationField(ExplorationFieldKind.PhantomJob, it.id.toString()),
            ExplorationField(ExplorationFieldKind.Level, "0")) }
        val section = ExplorationSection(ExplorationSectionKind.TreasureChests, listOf(
            treasure("幸福兔", "gold", Long.MAX_VALUE.toString()), treasure("撒娇罐", "gold", "1"),
            treasure("幸福兔", "future", "1"),
        ))
        val content = readyContent(PersonalDataShareBuilder.build(PersonalDataShareInput.Occult(identity,
            ExplorationUiState(ExplorationBoard.OccultCrescent, overview = occultOverview(jobRecords, section)),
            PhantomWeaponUiState(itemError = ExplorationError.InvalidResponse)),
            PersonalDataShareCatalogs(phantomJobs = jobs))) as OccultShareContent
        assertSame(PersonalDataShareOptional.Failed, content.treasure)
    }

    private fun dashboardState(
        sections: Map<PersonalDataDashboardSectionKind, PersonalDataDashboardSectionState>,
        catalogs: PersonalDataOfficialCatalogs = PersonalDataOfficialCatalogs(
            fish = mapOf(1 to FishKingCatalogEntry(1, 1, "Fish", "1"))),
        supplementary: PersonalDataSupplementaryCatalogs? = PersonalDataSupplementaryCatalogs(
            fishingAchievements = listOf(PersonalDataAchievementCatalogEntry(1, "Achievement", null, null))),
    ) = PersonalDataDashboardUiState(
        sections = sections,
        catalogs = catalogs,
        catalogStatus = PersonalDataDashboardLoadStatus.Loaded,
        supplementary = supplementary,
        supplementaryStatus = if (supplementary == null) PersonalDataDashboardLoadStatus.Idle else PersonalDataDashboardLoadStatus.Loaded,
    )

    private fun loaded(data: PersonalDataDashboardData) = PersonalDataDashboardSectionState(
        PersonalDataDashboardLoadStatus.Loaded, data)

    private fun frontlineLoaded(data: PersonalDataFrontlineData) = PersonalDataFrontlineSectionState(
        PersonalDataFrontlineLoadStatus.Loaded, data)

    private fun category(id: Int, major: Int) = PersonalDataVanityCategory(id, "Category $id", null, major, id)

    private fun vanity(
        category: Int,
        count: Long?,
        period: PersonalDataVanityPeriod = PersonalDataVanityPeriod.AllTime,
        name: String = "Item $category",
    ) = PersonalDataVanityUsage(period, category, 100 + category, name, 200 + category, count)

    private fun frontlineOverview(period: FrontlinePeriodKind, ranks: FrontlineRanks) = FrontlineOverviewRecord(
        period, 10, 5, 4, .5, 2.0, null, null, null, null, null,
        FrontlineAverages(), ranks)

    private fun frontlineJob(name: String, battles: Long?, period: FrontlinePeriodKind?) = FrontlineJobRecord(
        period, name, battles, null, null, null, null, null, null, FrontlineAverages())

    private fun best(kind: FrontlineBestKind) = FrontlineBestRecord(
        kind, "Map", null, "Job", FrontlinePlacement.First, 1, 2, 3, null, null, null, emptyList())

    private fun emptyRanks() = FrontlineRanks(null, null, null, null, null, null)

    private fun phantomJobs() = (0..23).map { id -> PhantomShareJob(id, "Job $id", 82271 + id,
        PhantomJobCatalog.levelCaps.getValue(id)) }

    private fun firstTwoWeaponDefinitions(): List<PhantomWeaponDefinition> = buildList {
        repeat(22) { add(PhantomWeaponDefinition(PhantomWeaponStage.Penumbrae, 1000 + it, "P$it", 2000 + it)) }
        repeat(22) { add(PhantomWeaponDefinition(PhantomWeaponStage.Umbrae, 1100 + it, "U$it", 2100 + it)) }
    }

    private fun explorationRecord(key: String, vararg fields: ExplorationField) = ExplorationRecord(key, "", fields.toList())

    private fun treasure(type: String, grade: String, count: String) = explorationRecord("$type-$grade-$count",
        ExplorationField(ExplorationFieldKind.BoxType, type),
        ExplorationField(ExplorationFieldKind.BoxGrade, grade),
        ExplorationField(ExplorationFieldKind.Quantity, count))

    private fun occultOverview(jobs: List<ExplorationRecord>, treasure: ExplorationSection) = ExplorationOverview(
        ExplorationBoard.OccultCrescent, true,
        listOf(ExplorationField(ExplorationFieldKind.KnowledgeLevel, "60")),
        listOf(
            ExplorationSection(ExplorationSectionKind.Overview, listOf(explorationRecord("overview",
                ExplorationField(ExplorationFieldKind.KnowledgeLevel, "60"),
                ExplorationField(ExplorationFieldKind.Fates, "10"),
                ExplorationField(ExplorationFieldKind.CriticalEncounters, "2")))),
            ExplorationSection(ExplorationSectionKind.PhantomJobs, jobs),
            treasure,
        ))

    private fun readyContent(result: PersonalDataShareBuildResult): PersonalDataShareContent {
        assertTrue("Expected Ready but was $result", result is PersonalDataShareBuildResult.Ready)
        return (result as PersonalDataShareBuildResult.Ready).document.content
    }

    private fun assertNotReady(result: PersonalDataShareBuildResult, reason: PersonalDataShareNotReadyReason) {
        assertEquals(PersonalDataShareBuildResult.NotReady(reason), result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> known(value: PersonalDataShareOptional<T>): T =
        (value as PersonalDataShareOptional.Known<T>).value
}
