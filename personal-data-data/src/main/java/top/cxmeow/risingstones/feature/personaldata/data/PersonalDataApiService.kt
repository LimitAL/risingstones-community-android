package top.cxmeow.risingstones.feature.personaldata.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.personaldata.domain.EmptyPersonalDataCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriod
import top.cxmeow.risingstones.feature.personaldata.domain.FrontlinePeriodKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataAvailability
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoardContent
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataEntry
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataField
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataIdentity
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetric
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataMetricUnit
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataOfficialCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareImage
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataShareResourceService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataPhantomWeaponService
import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponElement
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationException
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataDashboardService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataDashboardSectionKind
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFrontlineCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateService
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataUltimateSection
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogProvider
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataSupplementaryCatalogs
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataFishingRankingKind
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationBoard
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationSectionKind
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDashboard
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateDeathPoint
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterCatalog
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterDetail
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateEncounterSummary
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateJobStatistic
import top.cxmeow.risingstones.feature.personaldata.domain.UltimatePartnerStatistic
import top.cxmeow.risingstones.feature.personaldata.domain.UltimatePhaseProgress
import top.cxmeow.risingstones.feature.personaldata.domain.UltimateTeammate
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.feature.personaldata.data.phantomWeaponItemIconUrl as phantomItemIconUrl
import top.cxmeow.risingstones.feature.personaldata.data.phantomWeaponElementIconUrl as phantomElementIconUrl
import top.cxmeow.risingstones.feature.personaldata.data.phantomWeaponLensImageUrl as phantomLensImageUrl

class PersonalDataApiService(
    private val risingStonesClient: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val catalogProvider: PersonalDataCatalogProvider = EmptyPersonalDataCatalogProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val temporarySessionId: String = UUID.randomUUID().toString(),
) : PersonalDataPhantomWeaponService, PersonalDataDashboardService, PersonalDataFrontlineService,
    PersonalDataUltimateService, PersonalDataShareResourceService {
    private val reader = PersonalDataRequestReader(risingStonesClient, sessionProvider, json, temporarySessionId)
    private val exploration = ExplorationApiReader(risingStonesClient, sessionProvider, json)
    private val reading = PersonalDataReadingApiReader(reader)
    private val dashboard = PersonalDataDashboardApiReader(reader, reading)
    private val frontline = PersonalDataFrontlineApiReader(reader)
    private val ultimate = PersonalDataUltimateApiReader(reader)
    private val phantomWeaponCatalog = BundledPhantomWeaponCatalogProvider()
    private val shareCatalog = BundledPersonalDataShareCatalogProvider()

    override suspend fun fetchShareCatalogs() = shareCatalog.fetchShareCatalogs()
    override fun sharePageUrl(kind: PersonalDataShareKind) = shareCatalog.sharePageUrl(kind)
    override fun shareImageUrl(image: PersonalDataShareImage) = shareCatalog.shareImageUrl(image)

    override suspend fun fetchPhantomWeaponExploration() = run {
        requireExplorationAccess()
        val overview = exploration.overview(ExplorationBoard.OccultCrescent)
        requireExplorationAccess()
        phantomWeaponSnapshot(overview).also { requireExplorationAccess() }
    }

    override suspend fun fetchPhantomWeaponCatalog() = phantomWeaponCatalog.fetchPhantomWeaponCatalog()
    override fun phantomWeaponItemIconUrl(iconId: Int) = phantomItemIconUrl(iconId)
    override fun phantomWeaponElementIconUrl(element: PhantomWeaponElement) = phantomElementIconUrl(element)
    override fun phantomWeaponLensImageUrl(step: Int) = phantomLensImageUrl(step)

    private suspend fun requireExplorationAccess() {
        currentCoroutineContext().ensureActive()
        if (!hasCommunityIdentity) throw ExplorationException.Unavailable
    }

    override suspend fun fetchUltimateRecords() = ultimate.records()
    override suspend fun fetchUltimateSection(territoryType: Int, section: PersonalDataUltimateSection) = ultimate.section(territoryType, section)
    override fun ultimateCoverUrl(territoryType: Int) = personalDataUltimateCoverUrl(territoryType)
    override fun ultimateJobIconUrl(jobName: String) = personalDataUltimateJobIconUrl(jobName)
    override fun ultimateJobOrder(jobName: String) = personalDataUltimateJobOrder(jobName)
    override fun ultimateMedalImageUrl(territoryType: Int) = personalDataUltimateMedalImageUrl(territoryType)

    override suspend fun fetchFrontlineSection(section: PersonalDataFrontlineSection) = frontline.fetch(section)
    override suspend fun fetchFrontlineCatalogs() = PersonalDataFrontlineCatalogs(
        mapNames = personalDataFrontlineMapNames,
        achievements = fetchSupplementaryCatalogs().frontlineAchievements,
    )
    override fun frontlineJobIconUrl(jobName: String, hollow: Boolean) = personalDataFrontlineJobIconUrl(jobName, hollow)
    override fun frontlineCompanyFlagUrl(companyName: String) = personalDataFrontlineGrandCompanyImageUrl(companyName)
    override fun frontlineAchievementImageUrl() = personalDataFrontlineAchievementImageUrl()

    override suspend fun fetchDashboardSection(section: PersonalDataDashboardSectionKind) = dashboard.fetch(section)
    override suspend fun fetchSupplementaryCatalogs() =
        (catalogProvider as? PersonalDataSupplementaryCatalogProvider)?.fetchSupplementaryCatalogs()
            ?: PersonalDataSupplementaryCatalogs()
    override suspend fun fetchFishingRanking(kind: PersonalDataFishingRankingKind) = reading.fishingRanking(kind)
    override suspend fun fetchRaceUsage() = reading.raceUsage()
    override suspend fun fetchGlamourSetRecords() = reading.glamourSetRecords()
    override fun itemIconUrl(iconId: Int) = personalDataItemIconUrl(iconId)
    override fun achievementIconUrl(iconId: Int) = personalDataAchievementIconUrl(iconId)
    override fun raidImageUrl(imageId: Int) = personalDataRaidImageUrl(imageId)

    override suspend fun fetchExplorationOverview(board: ExplorationBoard) =
        exploration.overview(board)

    override suspend fun fetchExplorationHistory(
        board: ExplorationBoard,
        section: ExplorationSectionKind,
    ) = exploration.history(board, section)
    override val hasCommunityIdentity: Boolean
        get() = RisingStonesCapability.PersonalData in sessionProvider.capabilities

    override suspend fun fetchIdentity(): PersonalDataIdentity {
        val data = rising(
            "api/home/groupAndRole/getCharacterBindInfo",
            extraQuery = listOf(q("platform", 2)),
        ).obj("data") ?: throw PersonalDataException.MissingPayload
        return PersonalDataIdentity(
            data.text("character_name", "characterName").orEmpty().trim().ifBlank { "—" },
            data.text("area_name", "areaName").orEmpty(),
            data.text("group_name", "groupName").orEmpty(),
            data.text("avatar"),
        )
    }

    override suspend fun fetchAvailability(): PersonalDataAvailability {
        val data = rising("api/home/dataCenter/dataOpenStatus").obj("data")
            ?: throw PersonalDataException.MissingPayload
        return PersonalDataAvailability(data.mapValues { (_, value) -> value.scalarText.orEmpty() })
    }

    override suspend fun fetchBoardContent(board: PersonalDataBoard): PersonalDataBoardContent {
        if (board == PersonalDataBoard.Ultimate) throw PersonalDataException.UnsupportedBoard(board)
        val definition = definitions.getValue(board)
        return supervisorScope {
            val summaryTask = async { rising(definition.summaryPath).element("data") ?: JsonNull }
            val sectionTasks = definition.sections.map { section ->
                section to async {
                    sectionResult {
                        val data = rising(section.path).element("data") ?: JsonNull
                        section(data, section.id)
                    }
                }
            }
            val summaryRows = summaryTask.await().rows()
            val periods = if (board == PersonalDataBoard.Frontline) {
                summaryRows.mapNotNull { row ->
                    val kind = FrontlinePeriodKind.entries.firstOrNull {
                        it.wireValue == row["data_time"]?.scalarText
                    } ?: return@mapNotNull null
                    FrontlinePeriod(kind, metrics(row, definition.metrics))
                }
            } else emptyList()
            val summary = when (board) {
                PersonalDataBoard.Frontline -> periods.firstOrNull { it.kind == FrontlinePeriodKind.Total }?.metrics
                    ?: emptyList()
                else -> metrics(summaryRows.firstOrNull().orEmpty(), definition.metrics)
            }
            currentCoroutineContext().ensureActive()
            if (!hasCommunityIdentity) throw PersonalDataException.AuthenticationRequired
            PersonalDataBoardContent(
                board = board,
                metrics = summary,
                sections = sectionTasks.map { (definition, task) ->
                    task.await().fold(
                        onSuccess = { it },
                        onFailure = { PersonalDataSection(definition.id, error = "load_failed") },
                    )
                },
                frontlinePeriods = periods,
            )
        }
    }

    override suspend fun fetchUltimateDashboard(): UltimateDashboard {
        val rows = (rising("api/home/dataCenter/gaoNanFirst1").element("data") ?: JsonNull).rows()
        return UltimateDashboard(rows.mapNotNull(::ultimateSummary))
    }

    override suspend fun fetchUltimateEncounterDetail(
        summary: UltimateEncounterSummary,
    ): UltimateEncounterDetail = supervisorScope {
        val territory = summary.territoryType
        val team = asyncResult("team") { ultimateRows("gaoNanTeam2", territory).mapNotNull(::teammate) }
        val jobs = asyncResult("jobs") {
            ultimateRows("gaoNanJob3", territory).mapNotNull(::job).sortedByDescending(UltimateJobStatistic::clearTimes)
        }
        val partners = asyncResult("partners") {
            ultimateRows("gaoNanFriend4", territory).mapNotNull(::partner)
                .sortedByDescending(UltimatePartnerStatistic::jointBattleTimes)
        }
        val phases = asyncResult("phases") {
            ultimateRows("gaoNanPhase6", territory).mapNotNull(::phase)
                .sortedBy { it.reachedAt ?: Instant.MAX }
        }
        val deaths = asyncResult("deaths") { ultimateRows("gaoNanDeadPoint5", territory).mapNotNull(::deathPoint) }
        val teamResult = team.await()
        val jobsResult = jobs.await()
        val partnersResult = partners.await()
        val phasesResult = phases.await()
        val deathsResult = deaths.await()
        val results = listOf(teamResult, jobsResult, partnersResult, phasesResult, deathsResult)
        currentCoroutineContext().ensureActive()
        if (!hasCommunityIdentity) throw PersonalDataException.AuthenticationRequired
        UltimateEncounterDetail(
            summary,
            teamResult.value.orEmpty(),
            jobsResult.value.orEmpty(),
            partnersResult.value.orEmpty(),
            phasesResult.value.orEmpty(),
            deathsResult.value.orEmpty(),
            results.mapNotNull { it.error?.let { error -> it.key to "load_failed" } }.toMap(),
        )
    }

    override suspend fun fetchOfficialCatalogs(): PersonalDataOfficialCatalogs =
        catalogProvider.fetchCatalogs()

    private suspend fun ultimateRows(name: String, territoryType: Int): List<JsonObject> =
        (rising("api/home/dataCenter/$name", listOf(q("territory_type", territoryType))).element("data")
            ?: JsonNull).rows()

    private fun <T> kotlinx.coroutines.CoroutineScope.asyncResult(
        key: String,
        block: suspend () -> T,
    ) = async {
        sectionResult { block() }.fold(
            { SectionResult(key, it, null) },
            { SectionResult<T>(key, null, it) },
        )
    }

    private suspend fun <T> sectionResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: PersonalDataException.AuthenticationRequired) {
        throw failure
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        if (!hasCommunityIdentity) throw PersonalDataException.AuthenticationRequired
        Result.failure(failure)
    }

    private suspend fun rising(path: String, extraQuery: List<RisingStonesApiQueryItem> = emptyList()): JsonObject =
        reader.read(path, extraQuery)

    private fun section(data: JsonElement, id: String): PersonalDataSection = PersonalDataSection(
        id,
        data.rows().mapIndexed { index, row ->
            PersonalDataEntry(
                "$id-$index",
                entryTitleKeys.firstNotNullOfOrNull { row[it]?.scalarText?.takeIf(String::isNotBlank) }.orEmpty(),
                row.flatMap { (key, value) ->
                    if (key in ignoredKeys) emptyList() else value.flatten(key)
                }.sortedWith(compareBy<PersonalDataField> { it.key != "log_time" }.thenBy { it.key }),
            )
        },
    )

    private fun metrics(row: Map<String, JsonElement>, definitions: List<MetricDefinition>) =
        definitions.mapNotNull { definition ->
            val raw = (row[definition.field] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val value = when (definition.field) {
                "gc_id" -> raw // The official value is a Grand Company name, not a numeric id.
                "win_rate", "succ_rate" -> raw.fixedMetric(scale = 0, multiplier = 100.0)
                "kda" -> raw.fixedMetric(scale = 2)
                else -> raw.takeIf { it.toDoubleOrNull()?.isFinite() == true }
            } ?: return@mapNotNull null
            PersonalDataMetric(definition.field, value, definition.unit)
        }

    private fun ultimateSummary(row: JsonObject): UltimateEncounterSummary? {
        val territory = row.int("territory_type", "territoryType") ?: return null
        if (UltimateEncounterCatalog.find(territory) == null) return null
        return UltimateEncounterSummary(
            territory,
            row.int("clear_times", "clearTimes") ?: 0,
            row.int("enter_before_clear", "enterBeforeClear"),
            row.text("job_name", "jobName"),
            row.instant("log_time", "logTime"),
            row.long("elapsed_time", "elapsedTime"),
            row.int("dead_times", "deadTimes"),
        )
    }

    private fun teammate(row: JsonObject): UltimateTeammate? = UltimateTeammate(
        row.text("character_namee", "characterNamee") ?: return null,
        row.text("area_name", "areaName").orEmpty(),
        row.text("group_name", "groupName").orEmpty(),
        row.text("job_name", "jobName").orEmpty(),
    )
    private fun job(row: JsonObject): UltimateJobStatistic? = UltimateJobStatistic(
        row.text("job_name", "jobName") ?: return null,
        row.int("job_times", "jobTimes") ?: 0,
    )
    private fun partner(row: JsonObject): UltimatePartnerStatistic? = UltimatePartnerStatistic(
        row.text("team_chara_name", "teamCharaName") ?: return null,
        row.text("area_name", "areaName").orEmpty(),
        row.text("group_name", "groupName").orEmpty(),
        row.int("friend_times", "friendTimes") ?: 0,
    )
    private fun phase(row: JsonObject): UltimatePhaseProgress? = UltimatePhaseProgress(
        row.text("phase") ?: return null,
        row.instant("log_time", "logTime"),
    )
    private fun deathPoint(row: JsonObject): UltimateDeathPoint? = UltimateDeathPoint(
        row.double("point_x", "pointX") ?: return null,
        row.double("point_y", "pointY") ?: return null,
        row.text("period").orEmpty(),
        row.instant("dead_time", "deadTime"),
    )
}

private data class SectionResult<T>(val key: String, val value: T?, val error: Throwable?)
private data class MetricDefinition(val field: String, val unit: PersonalDataMetricUnit? = null)
private data class SectionDefinition(val id: String, val path: String)
private data class BoardDefinition(
    val summaryPath: String,
    val metrics: List<MetricDefinition>,
    val sections: List<SectionDefinition>,
)

private fun metric(field: String, unit: PersonalDataMetricUnit? = null) = MetricDefinition(field, unit)
private fun section(id: String, endpoint: String) = SectionDefinition(id, "api/home/dataCenter/$endpoint")
private val definitions = mapOf(
    PersonalDataBoard.Frontline to BoardDefinition(
        "api/home/dataCenter/frontline1TotalNew",
        listOf(
            metric("fight_times", PersonalDataMetricUnit.Times), metric("kda"),
            metric("kill_times", PersonalDataMetricUnit.Times), metric("win_rate", PersonalDataMetricUnit.Percent),
            metric("gc_id"), metric("pvp_rank", PersonalDataMetricUnit.Levels),
            metric("series_level", PersonalDataMetricUnit.Levels), metric("win_times", PersonalDataMetricUnit.Times),
            metric("assist_times", PersonalDataMetricUnit.Times), metric("dead_times", PersonalDataMetricUnit.Times),
            metric("clear_time", PersonalDataMetricUnit.Hours), metric("occupy_count"),
            metric("kill_rank"), metric("heal_rank"), metric("damaged_rank"),
            metric("damage_rank"), metric("dead_rank"), metric("assist_rank"),
        ),
        listOf(
            section("weekly", "frontline2WeekNew"), section("job", "frontline3JobNew"),
            section("best", "frontline4Best"), section("map", "frontline5Map"),
            section("mapJob", "frontline6MapJob"),
        ),
    ),
    PersonalDataBoard.Fishing to BoardDefinition(
        "api/home/dataCenter/fishTotal1",
        listOf(
            metric("total_times", PersonalDataMetricUnit.Times), metric("succ_rate", PersonalDataMetricUnit.Percent),
            metric("sea_times", PersonalDataMetricUnit.Times), metric("max_sea_score", PersonalDataMetricUnit.Points),
        ),
        listOf(
            section("fish", "fishNum2"), section("bait", "fishBait3"),
            section("bigFish", "fishBig4"), section("achievement", "fishAchieve5"),
        ),
    ),
    PersonalDataBoard.Savage to BoardDefinition(
        "api/home/dataCenter/getLingShiTotal",
        listOf(
            metric("territory_num", PersonalDataMetricUnit.Pieces), metric("enter_num", PersonalDataMetricUnit.Times),
            metric("finish_times", PersonalDataMetricUnit.Times), metric("elapsed_time", PersonalDataMetricUnit.Hours),
        ),
        listOf(section("territory", "getLingShi")),
    ),
    PersonalDataBoard.Glamour to BoardDefinition(
        "api/home/dataCenter/getDressTotal7",
        listOf(
            metric("washing_num", PersonalDataMetricUnit.Times), metric("color_times", PersonalDataMetricUnit.Times),
            metric("vanity_times", PersonalDataMetricUnit.Times),
        ),
        listOf(
            section("race", "getDressRace1"), section("color", "getDressColor2"),
            section("ornament", "getDressOrnament3"), section("vanity", "getDressVanity4"),
            section("fullset", "getDressFullset5"),
        ),
    ),
)

private val ignoredKeys = setOf(
    "character_id", "characterId", "uid", "user_id", "userId", "role_id", "roleId", "tempsuid",
)
private val entryTitleKeys = listOf(
    "name", "title", "label", "job_name", "territory_name", "fish_name", "item_name",
    "map_name", "achievement_name", "catalog_name", "Name", "log_time",
)

private fun String.fixedMetric(scale: Int, multiplier: Double = 1.0): String? {
    val number = toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    val scaled = (number * multiplier).takeIf(Double::isFinite) ?: return null
    // JS toFixed rounds the represented binary number, after the official percentage multiplication.
    return BigDecimal(scaled).setScale(scale, RoundingMode.HALF_UP).toPlainString()
}

private fun q(name: String, value: Any?) = RisingStonesApiQueryItem(name, value?.toString().orEmpty())
private fun JsonObject.element(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { this[it]?.takeUnless { value -> value is JsonNull } }
private fun JsonObject.text(vararg names: String): String? = (element(*names) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(vararg names: String): Int? = (element(*names) as? JsonPrimitive)?.flexInt()
private fun JsonObject.long(vararg names: String): Long? = (element(*names) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
private fun JsonObject.boolean(vararg names: String): Boolean? =
    (element(*names) as? JsonPrimitive)?.booleanOrNull
        ?: (element(*names) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.double(vararg names: String): Double? = (element(*names) as? JsonPrimitive)?.doubleOrNull
    ?: (element(*names) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
private fun JsonObject.obj(vararg names: String): JsonObject? = element(*names) as? JsonObject
private fun JsonObject.array(vararg names: String): List<JsonElement> = (element(*names) as? JsonArray).orEmpty()
private fun JsonObject.instant(vararg names: String): Instant? = element(*names).toInstantOrNull()
private fun JsonPrimitive.flexInt(): Int? = intOrNull ?: contentOrNull?.toIntOrNull() ?: doubleOrNull?.toInt()

private val JsonElement.scalarText: String?
    get() = when (this) {
        is JsonPrimitive -> when {
            isString -> contentOrNull?.trim()
            booleanOrNull != null -> booleanOrNull.toString()
            else -> doubleOrNull?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                ?: contentOrNull?.trim()
        }
        else -> null
    }

private fun JsonElement.rows(): List<JsonObject> = when (this) {
    is JsonArray -> map { it as? JsonObject ?: throw PersonalDataException.MissingPayload }
    is JsonObject -> {
        val wrapper = listOf("rows", "list", "data").firstOrNull(::containsKey)
        if (wrapper == null) listOf(this)
        else (this[wrapper] as? JsonArray)?.rows() ?: throw PersonalDataException.MissingPayload
    }
    else -> throw PersonalDataException.MissingPayload
}

private fun JsonElement.flatten(prefix: String): List<PersonalDataField> = when (this) {
    is JsonObject -> keys.sorted().flatMap { key ->
        if (key in ignoredKeys) emptyList() else getValue(key).flatten(if (prefix.isBlank()) key else "$prefix.$key")
    }
    is JsonArray -> flatMapIndexed { index, value -> value.flatten("$prefix[$index]") }
    else -> scalarText?.takeIf(String::isNotBlank)?.let { listOf(PersonalDataField(prefix, it)) }.orEmpty()
}

private fun JsonElement?.toInstantOrNull(): Instant? {
    val value = (this as? JsonPrimitive)?.contentOrNull ?: return null
    value.toLongOrNull()?.let { return if (it > 10_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }
    runCatching { return Instant.parse(value) }
    runCatching { return OffsetDateTime.parse(value).toInstant() }
    runCatching {
        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant()
    }
    return null
}
