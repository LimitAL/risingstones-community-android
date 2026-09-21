package top.cxmeow.risingstones.feature.personaldata.presentation

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.math.RoundingMode
import top.cxmeow.risingstones.feature.personaldata.domain.*

sealed interface PersonalDataShareInput {
    val identity: PersonalDataIdentity

    data class Dashboard(
        val kind: PersonalDataShareKind,
        override val identity: PersonalDataIdentity,
        val state: PersonalDataDashboardUiState,
    ) : PersonalDataShareInput

    data class Frontline(
        override val identity: PersonalDataIdentity,
        val state: PersonalDataFrontlineUiState,
    ) : PersonalDataShareInput

    data class Ultimate(
        override val identity: PersonalDataIdentity,
        val state: PersonalDataUltimateUiState,
    ) : PersonalDataShareInput

    data class Occult(
        override val identity: PersonalDataIdentity,
        val state: ExplorationUiState,
        val weapons: PhantomWeaponUiState,
    ) : PersonalDataShareInput
}

enum class PersonalDataShareNotReadyReason {
    Loading, Failed, Unavailable, InsufficientData, AuthenticationRequired,
}

sealed interface PersonalDataShareBuildResult {
    data class Ready(val document: PersonalDataShareDocument) : PersonalDataShareBuildResult
    data class NotReady(val reason: PersonalDataShareNotReadyReason) : PersonalDataShareBuildResult
}

/** Pure projection of one already-loaded presentation snapshot. It performs no business read. */
object PersonalDataShareBuilder {
    private val ultimateTerritories = UltimateEncounterCatalog.encounters.map { it.territoryType }

    fun build(
        input: PersonalDataShareInput,
        catalogs: PersonalDataShareCatalogs = PersonalDataShareCatalogs(),
    ): PersonalDataShareBuildResult {
        if (input.identity.characterName.isBlank()) return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        return when (input) {
            is PersonalDataShareInput.Dashboard -> buildDashboard(input)
            is PersonalDataShareInput.Frontline -> buildFrontline(input)
            is PersonalDataShareInput.Ultimate -> buildUltimate(input, catalogs)
            is PersonalDataShareInput.Occult -> buildOccult(input, catalogs)
        }
    }

    private fun buildDashboard(input: PersonalDataShareInput.Dashboard): PersonalDataShareBuildResult = when (input.kind) {
        PersonalDataShareKind.Fishing -> buildFishing(input)
        PersonalDataShareKind.Glamour -> buildGlamour(input)
        PersonalDataShareKind.Savage -> buildSavage(input)
        else -> notReady(PersonalDataShareNotReadyReason.Unavailable)
    }

    private fun buildFishing(input: PersonalDataShareInput.Dashboard): PersonalDataShareBuildResult {
        val state = input.state
        requiredDashboardReason(state, PersonalDataDashboardSectionKind.FishingSummary)?.let { return notReady(it) }
        requiredDashboardReason(state, PersonalDataDashboardSectionKind.BigFish)?.let { return notReady(it) }
        dashboardCatalogReason(state.catalogStatus, state.catalogFailure)?.let { return notReady(it) }
        dashboardCatalogReason(state.supplementaryStatus, state.supplementaryFailure)?.let { return notReady(it) }

        val overview = (state.sections[PersonalDataDashboardSectionKind.FishingSummary]?.data as? PersonalDataDashboardData.FishingSummary)
            ?.record ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val rows = (state.sections[PersonalDataDashboardSectionKind.BigFish]?.data as? PersonalDataDashboardData.BigFish)
            ?.rows ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        if (rows.size <= 1) return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val officialByName = state.catalogs?.fish.orEmpty().values.associateBy { it.name }
        val oceanByName = state.supplementary?.oceanFish.orEmpty().associateBy { it.name }
        val recent = rows.sortedKnownFirstDescending { it.caughtAt }.take(2).map { row ->
            FishingShareCatch(row, officialByName[row.name]?.iconId ?: oceanByName[row.name]?.iconId)
        }

        val achievement = optionalFishingAchievement(state)
        if (achievement is OptionalPending) return notReady(achievement.reason)
        val content = FishingShareContent(overview, recent, (achievement as OptionalReady).value)
        return ready(input.identity, content)
    }

    private fun optionalFishingAchievement(state: PersonalDataDashboardUiState): OptionalState<FishingShareAchievement> {
        val section = state.sections[PersonalDataDashboardSectionKind.FishingAchievements]
            ?: return OptionalPending(PersonalDataShareNotReadyReason.Loading)
        when (val reason = dashboardReason(section.status, section.failure)) {
            PersonalDataShareNotReadyReason.AuthenticationRequired -> return OptionalPending(reason)
            PersonalDataShareNotReadyReason.Loading -> return OptionalPending(reason)
            PersonalDataShareNotReadyReason.Failed,
            PersonalDataShareNotReadyReason.Unavailable -> return OptionalReady(PersonalDataShareOptional.Failed)
            PersonalDataShareNotReadyReason.InsufficientData -> return OptionalReady(PersonalDataShareOptional.Failed)
            null -> Unit
        }
        val rows = (section.data as? PersonalDataDashboardData.FishingAchievements)?.rows
            ?: return OptionalReady(PersonalDataShareOptional.Failed)
        val record = rows.sortedKnownFirstDescending { it.obtainedAt }.firstOrNull()
            ?: return OptionalReady(PersonalDataShareOptional.Empty)
        val catalog = state.supplementary?.fishingAchievements.orEmpty()
            .firstOrNull { it.achievementId == record.achievementId }
        return OptionalReady(PersonalDataShareOptional.Known(FishingShareAchievement(record, catalog)))
    }

    private fun buildGlamour(input: PersonalDataShareInput.Dashboard): PersonalDataShareBuildResult {
        val state = input.state
        val required = listOf(
            PersonalDataDashboardSectionKind.GlamourSummary,
            PersonalDataDashboardSectionKind.Sets,
            PersonalDataDashboardSectionKind.Vanity,
            PersonalDataDashboardSectionKind.Accessories,
        )
        required.forEach { requiredDashboardReason(state, it)?.let { reason -> return notReady(reason) } }
        dashboardCatalogReason(state.catalogStatus, state.catalogFailure)?.let { return notReady(it) }
        dashboardCatalogReason(state.supplementaryStatus, state.supplementaryFailure)?.let { return notReady(it) }

        val overview = (state.sections[PersonalDataDashboardSectionKind.GlamourSummary]?.data as? PersonalDataDashboardData.GlamourSummary)
            ?.record ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val sets = (state.sections[PersonalDataDashboardSectionKind.Sets]?.data as? PersonalDataDashboardData.Sets)?.rows
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val vanity = (state.sections[PersonalDataDashboardSectionKind.Vanity]?.data as? PersonalDataDashboardData.Vanity)?.rows
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val accessories = (state.sections[PersonalDataDashboardSectionKind.Accessories]?.data as? PersonalDataDashboardData.Accessories)?.rows
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val glamour = state.catalogs?.glamour ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        if (glamour.sets.isEmpty()) return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val categories = state.supplementary?.vanityCategories.orEmpty()
        if (categories.isEmpty()) return notReady(PersonalDataShareNotReadyReason.InsufficientData)

        val setById = glamour.sets.associateBy { it.mirageSetId }
        val joinedSets = sets.mapNotNull { record -> setById[record.setId]?.let { GlamourShareSet(record, it) } }
        val latestSets = joinedSets.sortedKnownFirstDescending { it.record.recordedAt }.take(4)
        val invalidMembership = sets.any { record -> setById.containsKey(record.setId) && record.hasInvalidItemIds }
        val completed = if (invalidMembership) null else sets.count { record ->
            val definition = setById[record.setId] ?: return@count false
            definition.items.count { it.itemId != 0 } == record.itemIds.size
        }
        val rate = completed?.let { count -> count.toBigDecimal().multiply(100.toBigDecimal())
            .divide(glamour.sets.size.toBigDecimal(), 1, RoundingMode.HALF_UP).toDouble() }

        val categoriesById = categories.associateBy { it.id }
        fun vanityFavorite(major: Int): PersonalDataShareOptional<GlamourShareVanityFavorite> {
            val candidates = vanity.filter { row -> row.count != 0L && categoriesById[row.categoryId]?.majorOrder == major }
            if (candidates.isEmpty()) return PersonalDataShareOptional.Empty
            if (candidates.any { it.count.isUnknownCount() }) return PersonalDataShareOptional.Failed
            val row = candidates.sortedByDescending { it.count }.first()
            return PersonalDataShareOptional.Known(GlamourShareVanityFavorite(row, categoriesById[row.categoryId]!!))
        }
        val accessoryFavorite = when {
            accessories.isEmpty() -> PersonalDataShareOptional.Empty
            accessories.any { it.rank.isUnknownCount() } -> PersonalDataShareOptional.Failed
            else -> {
                val row = accessories.sortedBy { it.rank }.first()
                PersonalDataShareOptional.Known(GlamourShareAccessoryFavorite(
                    row, glamour.fashionAccessories.firstOrNull { it.id == row.accessoryId },
                ))
            }
        }
        val favorites = GlamourShareFavorites(vanityFavorite(1), vanityFavorite(3), vanityFavorite(4), accessoryFavorite)
        return ready(input.identity, GlamourShareContent(overview, rate, latestSets, favorites))
    }

    private fun buildSavage(input: PersonalDataShareInput.Dashboard): PersonalDataShareBuildResult {
        val state = input.state
        requiredDashboardReason(state, PersonalDataDashboardSectionKind.SavageSummary)?.let { return notReady(it) }
        requiredDashboardReason(state, PersonalDataDashboardSectionKind.SavageRaids)?.let { return notReady(it) }
        dashboardCatalogReason(state.catalogStatus, state.catalogFailure)?.let { return notReady(it) }
        val overview = (state.sections[PersonalDataDashboardSectionKind.SavageSummary]?.data as? PersonalDataDashboardData.SavageSummary)
            ?.record ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val clears = (state.sections[PersonalDataDashboardSectionKind.SavageRaids]?.data as? PersonalDataDashboardData.SavageRaids)
            ?.rows?.takeIf { it.isNotEmpty() } ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val catalogs = state.catalogs?.savageSeries.orEmpty()
        if (catalogs.size != 7 || catalogs.any { it.tiers.isEmpty() || it.tiers.any { tier -> tier.raids.isEmpty() } }) {
            return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        }
        val byTerritory = clears.groupBy { it.territoryId }
        val series = catalogs.map { catalogSeries -> SavageShareSeries(catalogSeries, catalogSeries.tiers.map { catalogTier ->
            SavageShareTier(catalogTier, catalogTier.raids.map { SavageShareRaid(it, byTerritory[it.instanceId].orEmpty()) })
        }) }
        val latest = clears.filter { it.clearedAt != null }.maxByOrNull { it.clearedAt!! }
        return ready(input.identity, SavageShareContent(overview, series, latest))
    }

    private fun buildFrontline(input: PersonalDataShareInput.Frontline): PersonalDataShareBuildResult {
        val state = input.state
        val required = listOf(PersonalDataFrontlineSection.Overview, PersonalDataFrontlineSection.Jobs,
            PersonalDataFrontlineSection.Best)
        required.forEach { section -> frontlineReason(state.sections[section])?.let { return notReady(it) } }
        val overviewRows = (state.sections[PersonalDataFrontlineSection.Overview]?.data as? PersonalDataFrontlineData.Overview)?.rows
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val overall = overviewRows.firstOrNull { it.period == FrontlinePeriodKind.Total }
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val v51 = overviewRows.firstOrNull { it.period == FrontlinePeriodKind.Since51 }
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val jobs = (state.sections[PersonalDataFrontlineSection.Jobs]?.data as? PersonalDataFrontlineData.Jobs)?.rows
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val common = jobs.filter { it.jobName.isNotBlank() }
            .sortedWith(compareBy<FrontlineJobRecord> { it.battles.isUnknownCount() }.thenByDescending { it.battles })
            .take(5)
        val best = (state.sections[PersonalDataFrontlineSection.Best]?.data as? PersonalDataFrontlineData.Best)?.rows
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val kills = best.firstOrNull { it.kind == FrontlineBestKind.Kills }
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val assists = best.firstOrNull { it.kind == FrontlineBestKind.Assists }?.let { PersonalDataShareOptional.Known(it) }
            ?: PersonalDataShareOptional.Empty

        val latestAchievement = when (val achievementState = state.sections[PersonalDataFrontlineSection.Achievements]) {
            null -> return notReady(PersonalDataShareNotReadyReason.Loading)
            else -> when (val reason = frontlineReason(achievementState)) {
                PersonalDataShareNotReadyReason.AuthenticationRequired -> return notReady(reason)
                PersonalDataShareNotReadyReason.Loading -> return notReady(reason)
                PersonalDataShareNotReadyReason.Failed,
                PersonalDataShareNotReadyReason.Unavailable,
                PersonalDataShareNotReadyReason.InsufficientData -> PersonalDataShareOptional.Failed
                null -> {
                    val achievementRows = (achievementState.data as? PersonalDataFrontlineData.Achievements)?.rows
                        ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
                    val achievement = achievementRows.sortedKnownFirstDescending {
                        frontlineInstant(it.obtainedAt, state.zone)
                    }.firstOrNull()
                    achievement?.let { record -> PersonalDataShareOptional.Known(FrontlineShareAchievement(
                        record, state.catalogs?.achievements.orEmpty().firstOrNull { it.achievementId == record.achievementId },
                    )) } ?: PersonalDataShareOptional.Empty
                }
            }
        }
        return ready(input.identity, FrontlineShareContent(overall, validRanks(v51.ranks), common, kills, assists, latestAchievement))
    }

    private fun buildUltimate(
        input: PersonalDataShareInput.Ultimate,
        catalogs: PersonalDataShareCatalogs,
    ): PersonalDataShareBuildResult {
        ultimateReason(input.state.overviewStatus, input.state.overviewFailure)?.let { return notReady(it) }
        val records = input.state.records?.takeIf { it.isNotEmpty() }
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val achievementByTerritory = catalogs.ultimateAchievements.associateBy { it.territoryType }
        val progress = ultimateTerritories.map { territory -> UltimateShareProgress(
            territory,
            UltimateEncounterCatalog.find(territory),
            achievementByTerritory[territory],
            records.firstOrNull { it.territoryType == territory },
        ) }
        val timeline = records.sortedKnownFirstDescending { ultimateInstant(it.firstClearAt, input.state.zone) }.map { record ->
            UltimateShareTimelineEntry(record, UltimateEncounterCatalog.find(record.territoryType), achievementByTerritory[record.territoryType])
        }
        val first = timeline.firstOrNull()
        val header = first?.takeIf { ultimateInstant(it.record.firstClearAt, input.state.zone) != null }?.record?.territoryType
        val medal = first?.takeIf { timeline.size == 1 }?.achievement?.medalId
        val clearedKnown = records.map { it.territoryType }.toSet()
        return ready(input.identity, UltimateShareContent(progress, timeline, header, medal,
            clearedKnown.containsAll(ultimateTerritories)))
    }

    private fun buildOccult(
        input: PersonalDataShareInput.Occult,
        catalogs: PersonalDataShareCatalogs,
    ): PersonalDataShareBuildResult {
        val state = input.state
        when (state.error) {
            ExplorationError.AuthenticationRequired -> return notReady(PersonalDataShareNotReadyReason.AuthenticationRequired)
            ExplorationError.Unavailable -> return notReady(PersonalDataShareNotReadyReason.Unavailable)
            ExplorationError.Network, ExplorationError.InvalidResponse, ExplorationError.Business -> return notReady(PersonalDataShareNotReadyReason.Failed)
            null -> Unit
        }
        if (state.isLoadingOverview) return notReady(PersonalDataShareNotReadyReason.Loading)
        val overview = state.overview ?: return notReady(PersonalDataShareNotReadyReason.Loading)
        if (overview.board != ExplorationBoard.OccultCrescent || !overview.available) {
            return notReady(PersonalDataShareNotReadyReason.Unavailable)
        }
        val overviewSection = overview.sections.firstOrNull { it.kind == ExplorationSectionKind.Overview }
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val jobsSection = overview.sections.firstOrNull { it.kind == ExplorationSectionKind.PhantomJobs }
            ?: return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        if (overviewSection.failure != null || jobsSection.failure != null) return notReady(PersonalDataShareNotReadyReason.Failed)
        val phantomCatalog = catalogs.phantomJobs.sortedBy { it.id }
        if (phantomCatalog.map { it.id } != (0..23).toList()) return notReady(PersonalDataShareNotReadyReason.InsufficientData)
        val jobRecords = jobsSection.records.associateBy { it.field(ExplorationFieldKind.PhantomJob)?.toIntOrNull() }
        val jobs = phantomCatalog.map { catalog ->
            val raw = jobRecords[catalog.id]?.field(ExplorationFieldKind.Level)?.toNonnegativeInt()
            val valid = if (catalog.id == 0) raw?.takeIf { it <= 23 } else raw?.takeIf { it <= catalog.levelCap }
            OccultShareJob(catalog, valid)
        }

        val treasure = buildTreasure(overview.sections.firstOrNull { it.kind == ExplorationSectionKind.TreasureChests })
        val weapon = buildRecentWeapon(input.weapons, catalogs)
        if (weapon is OptionalPending) return notReady(weapon.reason)
        return ready(input.identity, OccultShareContent(overview, jobs, treasure, (weapon as OptionalReady).value))
    }

    private fun buildTreasure(section: ExplorationSection?): PersonalDataShareOptional<OccultShareTreasure> {
        if (section == null || section.failure != null) return PersonalDataShareOptional.Failed
        val included = section.records.filter { it.field(ExplorationFieldKind.BoxType) in setOf("幸福兔", "撒娇罐") }
        if (included.isEmpty()) return PersonalDataShareOptional.Empty
        val values = included.map { record ->
            val grade = record.field(ExplorationFieldKind.BoxGrade)
            val count = record.field(ExplorationFieldKind.Quantity)?.toNonnegativeLong()
            if (grade !in setOf("copper", "silver", "gold") || count == null) return PersonalDataShareOptional.Failed
            grade!! to count
        }
        val total = safeSum(values.map { it.second }) ?: return PersonalDataShareOptional.Failed
        val bronze = safeSum(values.filter { it.first == "copper" }.map { it.second }) ?: return PersonalDataShareOptional.Failed
        val silver = safeSum(values.filter { it.first == "silver" }.map { it.second }) ?: return PersonalDataShareOptional.Failed
        val gold = safeSum(values.filter { it.first == "gold" }.map { it.second }) ?: return PersonalDataShareOptional.Failed
        val bronzeFraction = total.takeIf { it > 0 }?.let { bronze.toDouble() / it }
        val silverFraction = total.takeIf { it > 0 }?.let { silver.toDouble() / it }
        val goldFraction = if (bronzeFraction == null || silverFraction == null) null else 1.0 - bronzeFraction - silverFraction
        return PersonalDataShareOptional.Known(OccultShareTreasure(total, bronze, silver, gold,
            bronzeFraction, silverFraction, goldFraction))
    }

    private fun buildRecentWeapon(
        state: PhantomWeaponUiState,
        catalogs: PersonalDataShareCatalogs,
    ): OptionalState<OccultShareWeapon> {
        if (state.isLoading || state.isLoadingCatalog) return OptionalPending(PersonalDataShareNotReadyReason.Loading)
        if (state.itemError != null || state.catalogError != null) return OptionalReady(PersonalDataShareOptional.Failed)
        val records = state.items ?: return OptionalReady(PersonalDataShareOptional.Failed)
        val definitions = state.catalog?.weapons?.filter { it.stage in listOf(PhantomWeaponStage.Penumbrae, PhantomWeaponStage.Umbrae) }
            ?: return OptionalReady(PersonalDataShareOptional.Failed)
        if (definitions.size != 44) return OptionalReady(PersonalDataShareOptional.Failed)
        val recordById = records.groupBy { it.itemId }
        val obtained = definitions.mapNotNull { definition ->
            recordById[definition.itemId]?.firstOrNull()?.let { OccultShareWeapon(definition, it, catalogs.weaponItemCategories[definition.itemId]) }
        }.sortedKnownFirstDescending { phantomInstant(it.record.firstAcquiredAt, state.zone) }
        return OptionalReady(obtained.firstOrNull()?.let { PersonalDataShareOptional.Known(it) } ?: PersonalDataShareOptional.Empty)
    }

    private fun requiredDashboardReason(
        state: PersonalDataDashboardUiState,
        section: PersonalDataDashboardSectionKind,
    ): PersonalDataShareNotReadyReason? = dashboardReason(state.sections[section]?.status, state.sections[section]?.failure)

    private fun dashboardReason(
        status: PersonalDataDashboardLoadStatus?,
        failure: PersonalDataDashboardFailure?,
    ): PersonalDataShareNotReadyReason? = when (status) {
        PersonalDataDashboardLoadStatus.Loaded -> null
        PersonalDataDashboardLoadStatus.AuthRequired -> PersonalDataShareNotReadyReason.AuthenticationRequired
        PersonalDataDashboardLoadStatus.Unavailable -> PersonalDataShareNotReadyReason.Unavailable
        PersonalDataDashboardLoadStatus.Failed -> if (failure == PersonalDataDashboardFailure.AuthenticationRequired)
            PersonalDataShareNotReadyReason.AuthenticationRequired else PersonalDataShareNotReadyReason.Failed
        PersonalDataDashboardLoadStatus.Idle, PersonalDataDashboardLoadStatus.Loading, null -> PersonalDataShareNotReadyReason.Loading
    }

    private fun dashboardCatalogReason(
        status: PersonalDataDashboardLoadStatus,
        failure: PersonalDataDashboardFailure?,
    ) = dashboardReason(status, failure)

    private fun frontlineReason(state: PersonalDataFrontlineSectionState?): PersonalDataShareNotReadyReason? = when (state?.status) {
        PersonalDataFrontlineLoadStatus.Loaded -> null
        PersonalDataFrontlineLoadStatus.AuthRequired -> PersonalDataShareNotReadyReason.AuthenticationRequired
        PersonalDataFrontlineLoadStatus.Unavailable -> PersonalDataShareNotReadyReason.Unavailable
        PersonalDataFrontlineLoadStatus.Failed -> if (state.failure == PersonalDataFrontlineFailure.AuthenticationRequired)
            PersonalDataShareNotReadyReason.AuthenticationRequired else PersonalDataShareNotReadyReason.Failed
        PersonalDataFrontlineLoadStatus.Idle, PersonalDataFrontlineLoadStatus.Loading, null -> PersonalDataShareNotReadyReason.Loading
    }

    private fun ultimateReason(
        status: PersonalDataUltimateLoadStatus,
        failure: PersonalDataUltimateFailure?,
    ): PersonalDataShareNotReadyReason? = when (status) {
        PersonalDataUltimateLoadStatus.Loaded -> null
        PersonalDataUltimateLoadStatus.AuthRequired -> PersonalDataShareNotReadyReason.AuthenticationRequired
        PersonalDataUltimateLoadStatus.Unavailable -> PersonalDataShareNotReadyReason.Unavailable
        PersonalDataUltimateLoadStatus.Failed -> if (failure == PersonalDataUltimateFailure.AuthenticationRequired)
            PersonalDataShareNotReadyReason.AuthenticationRequired else PersonalDataShareNotReadyReason.Failed
        PersonalDataUltimateLoadStatus.Idle, PersonalDataUltimateLoadStatus.Loading -> PersonalDataShareNotReadyReason.Loading
    }

    private fun validRanks(value: FrontlineRanks) = FrontlineRanks(
        value.kills.validRadar(), value.healing.validRadar(), value.damageTaken.validRadar(),
        value.damage.validRadar(), value.survival.validRadar(), value.assists.validRadar(),
    )

    private fun frontlineInstant(stamp: FrontlineDayStamp?, zone: ZoneId): Instant? = try { when (stamp) {
        is FrontlineDayStamp.CalendarDate -> stamp.value.atStartOfDay(zone).toInstant()
        is FrontlineDayStamp.LocalTime -> stamp.value.atZone(zone).toInstant()
        is FrontlineDayStamp.OffsetTime -> stamp.value
        null -> null
    } } catch (_: DateTimeException) { null }

    private fun ultimateInstant(time: UltimateRecordTime?, zone: ZoneId): Instant? = try { when (time) {
        is UltimateRecordTime.CalendarDate -> time.value.atStartOfDay(zone).toInstant()
        is UltimateRecordTime.LocalTime -> time.value.atZone(zone).toInstant()
        is UltimateRecordTime.OffsetTime -> time.value
        null -> null
    } } catch (_: DateTimeException) { null }

    private fun phantomInstant(time: PhantomWeaponRecordTime?, zone: ZoneId): Instant? = try { when (time) {
        is PhantomWeaponRecordTime.CalendarDate -> time.value.atStartOfDay(zone).toInstant()
        is PhantomWeaponRecordTime.LocalTime -> time.value.atZone(zone).toInstant()
        is PhantomWeaponRecordTime.OffsetTime -> time.value
        null -> null
    } } catch (_: DateTimeException) { null }

    private fun ExplorationRecord.field(kind: ExplorationFieldKind): String? =
        fields.firstOrNull { it.kind == kind }?.value?.trim()?.takeIf(String::isNotEmpty)

    private fun String.toNonnegativeInt(): Int? = toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.let {
        try { it.intValueExact() } catch (_: ArithmeticException) { null }
    }

    private fun String.toNonnegativeLong(): Long? = toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.let {
        try { it.longValueExact() } catch (_: ArithmeticException) { null }
    }

    private fun safeSum(values: List<Long>): Long? {
        var total = 0L
        for (value in values) {
            if (value < 0 || value > Long.MAX_VALUE - total) return null
            total += value
        }
        return total
    }

    private fun Double?.validRadar() = this?.takeIf { it.isFinite() && it in 0.0..100.0 }
    private fun Long?.isUnknownCount() = this?.let { it < 0 } ?: true

    private inline fun <T> List<T>.sortedKnownFirstDescending(crossinline time: (T) -> Instant?): List<T> =
        sortedWith(compareBy<T> { time(it) == null }.thenByDescending { time(it) })

    private fun ready(identity: PersonalDataIdentity, content: PersonalDataShareContent) =
        PersonalDataShareBuildResult.Ready(PersonalDataShareDocument(identity, content))

    private fun notReady(reason: PersonalDataShareNotReadyReason) = PersonalDataShareBuildResult.NotReady(reason)

    private sealed interface OptionalState<out T>
    private data class OptionalReady<T>(val value: PersonalDataShareOptional<T>) : OptionalState<T>
    private data class OptionalPending(val reason: PersonalDataShareNotReadyReason) : OptionalState<Nothing>
}
