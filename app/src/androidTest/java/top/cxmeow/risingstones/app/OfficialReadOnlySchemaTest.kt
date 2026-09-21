package top.cxmeow.risingstones.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.auth.webview.KeystoreRisingStonesCookieStore
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.network.OfficialRisingStonesEndpoints
import java.util.concurrent.TimeUnit

/** Opt-in device research. No credentials, response values, messages, or exception details are emitted. */
class OfficialReadOnlySchemaTest {
    @Test fun inspectReviewedGuildReads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("officialReadOnlyProbe") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("officialReadOnlyProbeGroup") == "guild")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val credential = KeystoreRisingStonesCookieStore(instrumentation.targetContext).read()
        check(credential != null) { "A manually established device session is required" }
        val authorizer = credential.authorizer()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        // Only these source-reviewed GETs can be selected. Values stay inside the device process.
        suspend fun read(endpoint: String, parameters: Map<String, String> = emptyMap()): JsonObject? {
            check(endpoint in ReviewedGuildEndpoints)
            val base = OfficialRisingStonesEndpoints.ApiBaseUrl.toHttpUrl()
                .resolve("api/home/$endpoint") ?: error("Invalid reviewed endpoint")
            val url = base.newBuilder().apply {
                parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val request = Request.Builder().url(url).get()
            authorizer.authorize(RisingStonesRequestContext(url.encodedPath,
                RisingStonesAuthenticationRequirement.Required), RisingStonesHeaderSink(request::header))
            var accepted: JsonObject? = null
            val result = try {
                client.newCall(request.build()).execute().use { response ->
                    val envelope = Json.parseToJsonElement(response.body.string()) as? JsonObject
                    val code = (envelope?.get("code") as? JsonPrimitive)?.intOrNull
                    if (response.isSuccessful && code in listOf(10000, 10002)) {
                        accepted = envelope?.get("data") as? JsonObject
                    }
                    buildJsonObject {
                        put("endpoint", endpoint) // Never include query values in the report.
                        put("httpStatus", response.code)
                        put("code", code ?: 0)
                        put("schema", shape(envelope?.get("data")))
                    }
                }
            } catch (_: Exception) {
                buildJsonObject { put("endpoint", endpoint); put("transportFailed", true) }
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nRISING_SCHEMA $result\n") })
            return accepted
        }
        fun JsonObject.positiveId(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.matches(Regex("[0-9]+")) && it.any { digit -> digit != '0' } }
        try {
            val basic = read("userInfo/getUserBasicInfo") ?: return@runBlocking
            val guildId = basic.positiveId("gc_id") ?: return@runBlocking
            val guildQuery = mapOf("guild_id" to guildId)
            val info = read("guild/getGuildInfo", guildQuery) ?: return@runBlocking
            val contract = buildJsonObject {
                put("guildIdMatchesOwn", info.positiveId("guild_id") == guildId)
                put("canonicalGuildIdMatchesOwn", info.positiveId("guild_id")?.trimStart('0') == guildId.trimStart('0'))
                for (field in listOf("guild_rank", "member_num", "active_member_num")) {
                    val value = info[field]
                    put("${field}_integer", value == null || value is JsonNull ||
                        (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { it >= 0 } == true)
                }
                for (field in listOf("house_public", "isGuildMember")) {
                    val value = info[field]
                    put("${field}_flag", value == null || value is JsonNull ||
                        (value as? JsonPrimitive)?.booleanOrNull != null ||
                        (value as? JsonPrimitive)?.contentOrNull in listOf("0", "1"))
                }
                put("labelsArrayOrNull", info["guild_label"] == null || info["guild_label"] is JsonNull || info["guild_label"] is JsonArray)
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nRISING_GUILD_CONTRACT $contract\n") })
            read("guild/getGuildMember", guildQuery) ?: return@runBlocking
            read("guild/guildMemberDynamic", guildQuery + mapOf("page" to "1", "limit" to "30"))
                ?: return@runBlocking
            val album = read("guild/getGuildPhotos", guildQuery + mapOf("page" to "1", "limit" to "20"))
                ?: return@runBlocking
            val photo = (album["rows"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@runBlocking
            val photoId = photo.positiveId("id") ?: return@runBlocking
            read("guild/getGuildPhotoDetail", mapOf("id" to photoId)) ?: return@runBlocking
            val comments = read("guild/GuildPhotoCommentDetail", mapOf("photo_id" to photoId,
                "page" to "1", "limit" to "20")) ?: return@runBlocking
            val comment = (comments["rows"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@runBlocking
            val commentId = comment.positiveId("id") ?: return@runBlocking
            read("guild/guildPhotoSubCommentDetail", mapOf("root_parent" to commentId,
                "order" to "earliest", "page" to "1", "limit" to "20"))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun schemaExcludesAllScalarValuesAndDynamicKeys() {
        val source = Json.parseToJsonElement("""{"uuid":"private fixture","rows":[{"name":"private fixture","value":987654321}],"2026-09-18":"private fixture"}""")
        val result = shape(source).toString()
        assertFalse(result.contains("private fixture"))
        assertFalse(result.contains("987654321"))
        assertFalse(result.contains("2026-09-18"))
        assertTrue(result.contains("nonempty"))
    }

    @Test fun inspectReviewedStatisticsReads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("officialReadOnlyProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val credential = KeystoreRisingStonesCookieStore(instrumentation.targetContext).read()
        check(credential != null) { "A manually established device session is required" }
        val authorizer = credential.authorizer()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        try {
            val endpoints = when (InstrumentationRegistry.getArguments().getString("officialReadOnlyProbeGroup")) {
                null, "statistics" -> ReviewedEndpoints
                "glamour" -> ReviewedGlamourEndpoints
                "glamour-search" -> ReviewedGlamourSearchEndpoints
                "guild-actions" -> ReviewedGuildActionEndpoints
                else -> error("Unknown reviewed probe group")
            }
            for (endpoint in endpoints) {
                val url = OfficialRisingStonesEndpoints.ApiBaseUrl.toHttpUrl()
                    .resolve("api/home/$endpoint") ?: error("Invalid reviewed endpoint")
                val builder = Request.Builder().url(url).get()
                authorizer.authorize(RisingStonesRequestContext(url.encodedPath,
                    RisingStonesAuthenticationRequirement.Required), RisingStonesHeaderSink(builder::header))
                val result = try {
                    client.newCall(builder.build()).execute().use { response ->
                        val envelope = Json.parseToJsonElement(response.body.string()) as? JsonObject
                        buildJsonObject {
                            put("endpoint", endpoint)
                            put("httpStatus", response.code)
                            put("code", (envelope?.get("code") as? JsonPrimitive)?.intOrNull ?: 0)
                            put("schema", shape(envelope?.get("data")))
                        }
                    }
                } catch (_: Exception) {
                    buildJsonObject { put("endpoint", endpoint); put("transportFailed", true) }
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nRISING_SCHEMA $result\n") })
                // An expired or conflicted session is not evidence about later endpoints.
                val code = (result["code"] as? JsonPrimitive)?.intOrNull
                if (code in listOf(10001, 10403, 10105) || result["transportFailed"] != null) break
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

private fun shape(value: JsonElement?, depth: Int = 0): JsonElement = when {
    value == null || value is JsonNull -> JsonPrimitive("null")
    depth >= 5 -> JsonPrimitive("nested")
    value is JsonObject -> buildJsonObject {
        value.entries.filter { it.key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }.take(80)
            .forEach { (key, item) -> put(key, shape(item, depth + 1)) }
    }
    value is JsonArray -> buildJsonObject {
        put("type", "array"); put("nonempty", value.isNotEmpty())
        value.firstOrNull()?.let { put("item", shape(it, depth + 1)) }
    }
    value is JsonPrimitive && value.isString -> JsonPrimitive("string")
    value is JsonPrimitive && value.booleanOrNull != null -> JsonPrimitive("boolean")
    else -> JsonPrimitive("number")
}

// Only methods confirmed as reads in the public SPA. No message/follower/acknowledgement endpoints.
private val ReviewedEndpoints = listOf(
    "dataCenter/dataOpenStatus",
    "dataCenter/getMKDTotal1",
    "dataCenter/getMKDSupportJob2",
    "dataCenter/getMKDItemUse3",
    "dataCenter/getMKDItemGet4",
    "dataCenter/getMKDItemBox5",
    "dataCenter/getMKDAchieve7",
    "dataCenter/getMKDLight8",
    "dataCenter/getDDTerr1?dd_type=dd4",
    "dataCenter/getDDItem3?dd_type=dd4",
    "dataCenter/getDDHistory4?dd_type=dd4&catalog_type=treasure",
    "dataCenter/getDDAchieve5?dd_type=dd4",
    "dataCenter/getDDDeadPoint6?dd_type=dd4",
    "dataCenter/getDDGaoNan2?territory_type=1311",
    "dataCenter/getDDFirstTeam7?territory_type=1311",
)

private val ReviewedGlamourEndpoints = listOf(
    "glamour/tagList",
    "gameData/getAllRace",
    "gameData/tribeList",
    "glamour/glamoursFollowList?page=1&limit=2",
    "glamour/myGlamoursList?page=1&limit=2&order=latest&title=",
    "glamour/myGlamoursNum",
    "glamour/glamoursList?page=1&limit=2&order=latest&tag_ids=1",
)

private val ReviewedGlamourSearchEndpoints = listOf(
    "gameData/searchEquip?page=1&limit=20&name=%E7%A4%BC%E6%9C%8D",
    "gameData/getGlassesList?name=%E7%9C%BC%E9%95%9C",
    "gameData/getOrnamentList?name=%E4%BC%9E",
    "glamour/myFavoritesList?page=1&limit=10",
)

private val ReviewedGuildEndpoints = setOf(
    "userInfo/getUserBasicInfo", "guild/getGuildInfo", "guild/getGuildMember", "guild/guildMemberDynamic",
    "guild/getGuildPhotos", "guild/getGuildPhotoDetail", "guild/GuildPhotoCommentDetail",
    "guild/guildPhotoSubCommentDetail",
)

// Relationship and label reads only; upload-token issuance and every mutation are excluded.
private val ReviewedGuildActionEndpoints = listOf(
    "groupAndRole/getCharacterBindInfo?platform=1",
    "guild/getGuildLabelList",
)
