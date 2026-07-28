package top.cxmeow.risingstones.feature.personaldata.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesIdentityConflictResolver
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
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
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
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
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class PersonalDataApiService(
    private val risingStonesClient: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val catalogProvider: PersonalDataCatalogProvider = EmptyPersonalDataCatalogProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val temporarySessionId: String = UUID.randomUUID().toString(),
) : PersonalDataService {
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
                    runCatching {
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
                    ?: metrics(summaryRows.firstOrNull().orEmpty(), definition.metrics)
                PersonalDataBoard.Savage, PersonalDataBoard.Glamour -> aggregateMetrics(summaryRows, definition.metrics)
                else -> metrics(summaryRows.firstOrNull().orEmpty(), definition.metrics)
            }
            PersonalDataBoardContent(
                board = board,
                metrics = summary,
                sections = sectionTasks.map { (definition, task) ->
                    task.await().fold(
                        onSuccess = { it },
                        onFailure = { PersonalDataSection(definition.id, error = it.message ?: it.toString()) },
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
        UltimateEncounterDetail(
            summary,
            teamResult.value.orEmpty(),
            jobsResult.value.orEmpty(),
            partnersResult.value.orEmpty(),
            phasesResult.value.orEmpty(),
            deathsResult.value.orEmpty(),
            results.mapNotNull { it.error?.let { error -> it.key to (error.message ?: error.toString()) } }.toMap(),
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
        runCatching { block() }.fold(
            { SectionResult(key, it, null) },
            { SectionResult<T>(key, null, it) },
        )
    }

    private suspend fun rising(path: String, extraQuery: List<RisingStonesApiQueryItem> = emptyList()): JsonObject {
        val capability = RisingStonesCapability.PersonalData
        val initial = if (capability in sessionProvider.capabilities) {
            sessionProvider.currentAuthorizer()
        } else {
            null
        } ?: throw PersonalDataException.AuthenticationRequired
        val request = RisingStonesApiRequest(
            path = path,
            query = extraQuery + q("tempsuid", temporarySessionId),
        )
        suspend fun execute(authorizer: RisingStonesRequestAuthorizer): JsonObject {
            val headers = mutableMapOf<String, String>()
            authorizer.authorize(
                RisingStonesRequestContext(
                    path = path,
                    requirement = RisingStonesAuthenticationRequirement.Required,
                    capability = capability,
                ),
                RisingStonesHeaderSink(headers::set),
            )
            return json.parseToJsonElement(
                risingStonesClient.execute(request.copy(headers = request.headers + headers)).body.decodeToString(),
            ).jsonObject
        }
        var response = try {
            execute(initial)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            when {
                error.isIdentityConflict() -> execute(
                    (sessionProvider as? RisingStonesIdentityConflictResolver)
                        ?.awaitIdentityConflictResolution() ?: throw error,
                )
                error.isAuthenticationFailure() -> execute(sessionProvider.refreshAuthorizer() ?: throw error)
                else -> throw error
            }
        }
        if (response.int("code") == 10105) {
            (sessionProvider as? RisingStonesIdentityConflictResolver)
                ?.awaitIdentityConflictResolution()
                ?.let { response = execute(it) }
        } else if (response.isAuthenticationFailure()) {
            sessionProvider.refreshAuthorizer()?.let { response = execute(it) }
        }
        val code = response.int("code") ?: 0
        if (code != 10000) throw PersonalDataException.Business(code, response.text("msg", "message"))
        return response
    }

    private fun section(data: JsonElement, id: String): PersonalDataSection = PersonalDataSection(
        id,
        data.rows().mapIndexed { index, row ->
            PersonalDataEntry(
                "$id-$index",
                entryTitleKeys.firstNotNullOfOrNull { row[it]?.scalarText }.orEmpty(),
                row.flatMap { (key, value) ->
                    if (key in ignoredKeys) emptyList() else value.flatten(key)
                }.sortedWith(compareBy<PersonalDataField> { it.key != "log_time" }.thenBy { it.key }),
            )
        },
    )

    private fun metrics(row: Map<String, JsonElement>, definitions: List<MetricDefinition>) =
        definitions.mapNotNull { definition ->
            row[definition.field]?.scalarText?.takeIf(String::isNotBlank)?.let {
                PersonalDataMetric(definition.field, it, definition.unit)
            }
        }

    private fun aggregateMetrics(rows: List<JsonObject>, definitions: List<MetricDefinition>) =
        definitions.mapNotNull { definition ->
            val values = rows.mapNotNull { it[definition.field]?.scalarText?.toDoubleOrNull() }
            if (values.isEmpty()) null else PersonalDataMetric(
                definition.field,
                values.sum().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() },
                definition.unit,
            )
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
    "map_name", "achievement_name", "log_time",
)

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
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf("rows", "list", "data").firstNotNullOfOrNull { key ->
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
    } ?: listOf(this)
    else -> emptyList()
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

private fun JsonObject.isAuthenticationFailure(): Boolean {
    val code = int("code")
    if (code == 10105) return false
    if (code in setOf(401, 403, 10002, 10003, 10004, 10005, 10403)) return true
    val message = text("msg", "message").orEmpty().lowercase()
    return listOf("未登录", "登录失效", "登录过期", "token失效", "unauthorized", "session expired")
        .any(message::contains)
}
private fun Throwable.isAuthenticationFailure(): Boolean = causeChain().any {
    it is RisingStonesHttpException.ServerResponse && it.statusCode in setOf(401, 403)
}
private fun Throwable.isIdentityConflict(): Boolean = causeChain().any { cause ->
    cause is RisingStonesHttpException.ServerResponse && runCatching {
        Json.parseToJsonElement(cause.responseBody.decodeToString()).jsonObject.int("code") == 10105
    }.getOrDefault(false)
}
private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
