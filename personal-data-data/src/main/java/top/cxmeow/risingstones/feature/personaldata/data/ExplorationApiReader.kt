package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.personaldata.domain.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationFieldKind.*
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationSectionKind.*
import top.cxmeow.risingstones.network.*

internal class ExplorationApiReader(
    private val client: RisingStonesPublicApiClient,
    private val session: RisingStonesSessionProvider,
    private val json: Json,
) {
    suspend fun overview(board: ExplorationBoard): ExplorationOverview = supervisorScope {
        val availability = get("dataOpenStatus") as? JsonObject ?: throw ExplorationException.InvalidResponse
        val key = if (board == ExplorationBoard.OccultCrescent) "mkd" else "page_a"
        val available = availability.text(key)?.toIntOrNull() ?: throw ExplorationException.InvalidResponse
        if (available != 1) return@supervisorScope ExplorationOverview(board, false, emptyList(), emptyList())
        val definitions = if (board == ExplorationBoard.OccultCrescent) OccultDefinitions else DeepDefinitions
        val tasks = definitions.map { definition -> async { readSection(definition) } }
        val sections = tasks.map { it.await() }.toMutableList()
        if (board == ExplorationBoard.DeepDungeon) {
            // The official overview has both solo and party records. Keep their statistics separate.
            sections += jobSection(sections.first { it.kind == Challenges }, ChallengeJobs)
            sections += jobSection(sections.first { it.kind == SpecialBattle }, SpecialBattleJobs)
            val challenges = sections.first { it.kind == Challenges }
            sections += ExplorationSection(FailureFloors, challenges.records.filter { it.key.startsWith("floor-") }, challenges.failure)
            for (index in sections.indices) {
                if (sections[index].kind in listOf(Challenges, SpecialBattle)) {
                    sections[index] = sections[index].copy(records = sections[index].records.filterNot {
                        it.key.startsWith("job-") || it.key.startsWith("floor-")
                    })
                }
            }
        }
        val summary = sections.firstOrNull { it.kind == Overview }
        requireAccess()
        ExplorationOverview(board, true, summary?.records?.firstOrNull()?.fields.orEmpty(), sections)
    }

    suspend fun history(board: ExplorationBoard, kind: ExplorationSectionKind): ExplorationSection {
        val definition = when (board) {
            ExplorationBoard.OccultCrescent -> when (kind) {
                TreasureHistory -> Definition(kind, "getMKDIHistory6", HistoryFields, listOf(q("catalog_type", "稀有道具")))
                RelicHistory -> Definition(kind, "getMKDIHistory6", HistoryFields, listOf(q("catalog_type", "半魂晶")))
                else -> throw ExplorationException.Unavailable
            }
            ExplorationBoard.DeepDungeon -> when (kind) {
                TreasureHistory, ItemHistory -> Definition(kind, "getDDHistory4", HistoryFields,
                    DeepQuery + q("catalog_type", if (kind == TreasureHistory) "treasure" else "item"))
                else -> throw ExplorationException.Unavailable
            }
        }
        return readSection(definition)
    }

    private suspend fun readSection(definition: Definition): ExplorationSection = try {
        val data = get(definition.path, definition.query) as? JsonArray ?: throw ExplorationException.InvalidResponse
        val records = data.mapIndexed { index, element ->
            requireAccess()
            val row = element as? JsonObject ?: throw ExplorationException.InvalidResponse
            val fields = definition.fields.mapNotNull { (key, kind) -> row.text(key)?.takeIf(String::isNotBlank)?.let {
                ExplorationField(kind, it)
            } }.distinctBy { it.kind }
            val itemId = row.text("catalog_id")?.toIntOrNull()
            val achievementId = row.text("achieve_id")?.toIntOrNull()
            if (fields.isEmpty() && itemId == null && achievementId == null) throw ExplorationException.InvalidResponse
            ExplorationRecord("${definition.kind}-$index", row.text("catalog_name", "achieve_name")
                ?.takeIf(String::isNotBlank) ?: itemId?.let { OfficialExplorationItems.names[it] }.orEmpty(), fields,
                itemId, achievementId)
        }.toMutableList()
        if (definition.kind == PhantomJobs) {
            val mastered = records.distinctBy { it.value(PhantomJob) }.count { record ->
                val id = record.value(PhantomJob)?.toIntOrNull()
                val level = record.value(Level)?.toIntOrNull()
                id != null && id != 0 && level != null && PhantomJobCatalog.levelCaps[id] == level
            }
            records.removeAll { it.value(PhantomJob) == "0" }
            records.add(0, ExplorationRecord("freelancer", "", listOf(ExplorationField(PhantomJob, "0"),
                ExplorationField(Level, mastered.toString()))))
        }
        if (definition.kind in listOf(Challenges, SpecialBattle)) {
            // Expand only the documented job:count pairs. No raw composite field reaches presentation.
            data.forEachIndexed { index, element ->
                val row = element as JsonObject
                row.text("job_clear_times").orEmpty().split(',').forEach { pair ->
                    val parts = pair.split(':')
                    if (parts.size == 2 && (parts[0].toIntOrNull() ?: 0) > 0 && (parts[1].toLongOrNull() ?: -1) >= 0) {
                        records += ExplorationRecord("job-$index-${parts[0]}", "", listOfNotNull(
                            row.text("is_solo")?.let { ExplorationField(Solo, it) },
                            ExplorationField(ClassJob, parts[0]), ExplorationField(Clears, parts[1])))
                    }
                }
            }
        }
        if (definition.kind == Challenges) {
            data.forEachIndexed { index, element ->
                val row = element as JsonObject
                row.text("annihilation_num").orEmpty().split(',').forEach { pair ->
                    val parts = pair.split(':')
                    val territory = parts.firstOrNull()?.toIntOrNull()
                    if (parts.size == 2 && territory != null && territory > 0 && (parts[1].toLongOrNull() ?: -1) >= 0) {
                        // Official dd4 territory catalogue maps 1281..1290 to consecutive ten-floor ranges.
                        val floor = if (territory in 1281..1290) "${(territory - 1281) * 10 + 1}–${(territory - 1280) * 10}" else null
                        records += ExplorationRecord("floor-$index-$territory", "", listOfNotNull(
                            row.text("is_solo")?.let { ExplorationField(Solo, it) },
                            floor?.let { ExplorationField(Floor, it) }, ExplorationField(Wipes, parts[1])), territoryId = territory)
                    }
                }
            }
        }
        requireAccess()
        ExplorationSection(definition.kind, records.distinctBy { it.key })
    } catch (error: CancellationException) { throw error
    } catch (error: ExplorationException) {
        requireAccess()
        when (error) {
            ExplorationException.AuthenticationRequired, ExplorationException.Unavailable -> throw error
            else -> ExplorationSection(definition.kind, failure = when (error) {
                ExplorationException.InvalidResponse -> ExplorationFailure.InvalidResponse
                is ExplorationException.Business -> ExplorationFailure.Business
                else -> ExplorationFailure.Network
            })
        }
    }

    private suspend fun get(endpoint: String, query: List<RisingStonesApiQueryItem> = emptyList()): JsonElement {
        requireAccess()
        return try {
            val authorizer = session.currentAuthorizer() ?: throw ExplorationException.AuthenticationRequired
            requireAccess()
            try { request(authorizer, endpoint, query) } catch (_: ExplorationException.AuthenticationRequired) {
                requireAccess()
                val refreshed = session.refreshAuthorizer() ?: throw ExplorationException.AuthenticationRequired
                requireAccess()
                request(refreshed, endpoint, query)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: ExplorationException) { requireAccess(); throw error
        } catch (_: Exception) { requireAccess(); throw ExplorationException.Network }
    }

    private suspend fun request(authorizer: RisingStonesRequestAuthorizer, endpoint: String,
        query: List<RisingStonesApiQueryItem>): JsonElement {
        requireAccess()
        val path = "api/home/dataCenter/$endpoint"
        val headers = linkedMapOf<String, String>()
        authorizer.authorize(RisingStonesRequestContext(path, RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.PersonalData), RisingStonesHeaderSink { key, value -> headers[key] = value })
        requireAccess()
        val response = try { client.execute(RisingStonesApiRequest(path, query = query, headers = headers))
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            requireAccess()
            val status = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<RisingStonesHttpException.ServerResponse>().firstOrNull()?.statusCode
            if (status in listOf(401, 403)) throw ExplorationException.AuthenticationRequired
            throw ExplorationException.Network
        }
        requireAccess()
        if (response.statusCode in listOf(401, 403)) throw ExplorationException.AuthenticationRequired
        if (response.statusCode !in 200..299) throw ExplorationException.Network
        val root = try { json.parseToJsonElement(response.body.decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) { null } ?: throw ExplorationException.InvalidResponse
        val code = root.text("code")?.toIntOrNull()
        if (RisingStonesResponsePolicy.accepts(code)) {
            return root["data"]?.takeUnless { it is JsonNull } ?: throw ExplorationException.InvalidResponse
        }
        when (code) {
            10001, 10403, 10105 -> throw ExplorationException.AuthenticationRequired
            null -> throw ExplorationException.InvalidResponse
            else -> throw ExplorationException.Business(code)
        }
    }

    private suspend fun requireAccess() {
        currentCoroutineContext().ensureActive()
        if (RisingStonesCapability.PersonalData !in session.capabilities) throw ExplorationException.Unavailable
    }
}

private data class Definition(val kind: ExplorationSectionKind, val path: String,
    val fields: List<Pair<String, ExplorationFieldKind>>, val query: List<RisingStonesApiQueryItem> = emptyList())
private fun q(key: String, value: String) = RisingStonesApiQueryItem(key, value)
private fun JsonObject.text(vararg keys: String) = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull }
private fun ExplorationRecord.value(kind: ExplorationFieldKind) = fields.firstOrNull { it.kind == kind }?.value
private fun jobSection(source: ExplorationSection, kind: ExplorationSectionKind): ExplorationSection =
    ExplorationSection(kind, source.records.filter { it.key.startsWith("job-") }, source.failure)

private val DeepQuery = listOf(q("dd_type", "dd4"))
private val SpecialQuery = listOf(q("territory_type", "1311"))
private val HistoryFields = listOf("catalog_name" to ItemName, "catalog_type" to ItemCategory,
    "log_time" to RecordedAt, "get_num" to Quantity)
private val ItemFields = listOf("catalog_name" to ItemName, "catalog_type" to ItemCategory,
    "get_num" to Quantity, "first_time" to FirstAcquiredAt)
private val AchievementFields = listOf("achieve_name" to AchievementName, "log_time" to RecordedAt)
private val OccultDefinitions = listOf(
    Definition(Overview, "getMKDTotal1", listOf("now_level" to KnowledgeLevel, "fate_times" to Fates,
        "ce_times" to CriticalEncounters, "silver_num" to SilverCoins, "gold_num" to GoldCoins,
        "white_silver_num" to WhiteSilverCoins, "white_gold_num" to WhiteGoldCoins)),
    Definition(PhantomJobs, "getMKDSupportJob2", listOf("support_job" to PhantomJob, "now_level" to Level)),
    Definition(ItemUsage, "getMKDItemUse3", listOf("catalog_name" to ItemName, "use_num" to Uses)),
    Definition(AcquiredItems, "getMKDItemGet4", ItemFields),
    Definition(TreasureChests, "getMKDItemBox5", listOf("box_type" to BoxType, "box_level" to BoxGrade, "num" to Quantity)),
    Definition(Achievements, "getMKDAchieve7", AchievementFields),
    Definition(Aether, "getMKDLight8", listOf("color" to AetherColor, "quest_point" to AetherPoints)),
)
private val DeepDefinitions = listOf(
    Definition(Challenges, "getDDTerr1", listOf("is_solo" to Solo, "weapon_level" to WeaponLevel,
        "armor_level" to ArmorLevel, "total_clear_time" to Clears, "class_job" to ClassJob,
        "first_time" to FirstClearAt, "clear_elapsed_time" to ClearDuration,
        "total_dead_num" to Deaths), DeepQuery),
    Definition(SpecialBattle, "getDDGaoNan2", listOf("clear_times" to Clears, "class_job" to ClassJob,
        "log_time" to FirstClearAt, "elapsed_time" to ClearDuration, "enter_before_clear" to Attempts,
        "dead_times" to Deaths), SpecialQuery),
    Definition(AcquiredItems, "getDDItem3", ItemFields, DeepQuery),
    Definition(Achievements, "getDDAchieve5", AchievementFields, DeepQuery),
    Definition(DeathLocations, "getDDDeadPoint6", listOf("point_x" to X, "point_y" to Y), DeepQuery),
    Definition(FirstClearTeam, "getDDFirstTeam7", listOf("character_name" to CharacterName,
        "area_name" to Area, "group_name" to World, "job_name" to ClassJob), SpecialQuery),
)
