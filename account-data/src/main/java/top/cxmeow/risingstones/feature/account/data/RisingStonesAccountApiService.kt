package top.cxmeow.risingstones.feature.account.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import top.cxmeow.risingstones.core.auth.RisingStonesExplicitCapabilityProvider
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesHeaderSink
import top.cxmeow.risingstones.core.auth.RisingStonesIdentityConflictResolver
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountActionVerificationService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountDashboard
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountException
import top.cxmeow.risingstones.feature.account.domain.RisingStonesAccountService
import top.cxmeow.risingstones.feature.account.domain.RisingStonesCharacter
import top.cxmeow.risingstones.feature.account.domain.RisingStonesDailySignInResult
import top.cxmeow.risingstones.feature.account.domain.RisingStonesReward
import top.cxmeow.risingstones.feature.account.domain.RisingStonesRewardStatus
import top.cxmeow.risingstones.feature.account.domain.RisingStonesSignInLog
import top.cxmeow.risingstones.feature.account.domain.RisingStonesSignInSummary
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest
import top.cxmeow.risingstones.network.RisingStonesHttpException
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesResponsePolicy

class RisingStonesAccountApiService(
    private val client: RisingStonesPublicApiClient,
    private val sessionProvider: RisingStonesSessionProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val signInMonth: () -> String = {
        YearMonth.now(ZoneId.of("Asia/Shanghai")).toString()
    },
) : RisingStonesAccountService, RisingStonesAccountActionVerificationService {
    override val canVerifyDailySignIn: Boolean
        get() = (sessionProvider as? RisingStonesExplicitCapabilityProvider)
            ?.canAttemptCapability(RisingStonesCapability.DailySignIn) == true

    override suspend fun fetchSignInSummary(): RisingStonesSignInSummary {
        val logData = perform(
            path = "api/home/sign/mySignLog",
            capability = RisingStonesCapability.AccountRead,
            query = listOf(q("month", signInMonth())),
        ).objectValue("data")
        val logs = logData?.get("rows").dictionaryRows().map { row ->
            RisingStonesSignInLog(
                id = row.stringValue("id") ?: UUID.randomUUID().toString(),
                signTime = row.stringValue("sign_time", "signTime") ?: "-",
                platform = row.intValue("platform"),
                location = row.stringValue("ip_location", "ipLocation"),
            )
        }
        val rewards = runCatching {
            perform(
                path = "api/home/sign/signRewardList",
                capability = RisingStonesCapability.AccountRead,
            )["data"].dictionaryRows().mapIndexed { index, reward ->
                val id = reward.intValue("id") ?: index + 1
                val itemDescription = reward.stringValue("item_desc", "itemDesc")
                val itemName = reward.stringValue("item_name", "itemName")
                val description = if (!itemDescription.isNullOrBlank() && !itemName.isNullOrBlank()) {
                    "$itemDescription：$itemName"
                } else {
                    listOf(
                        "item_desc",
                        "itemDesc",
                        "item_name",
                        "itemName",
                        "description",
                        "reward_name",
                        "rewardName",
                        "name",
                        "title",
                    ).firstNotNullOfOrNull {
                        reward.stringValue(it)?.takeIf(String::isNotBlank)
                    } ?: defaultRewardDescription(id)
                }
                RisingStonesReward(
                    id = id,
                    description = description,
                    itemName = itemName,
                    requiredDays = reward.intValue("rule"),
                    status = when (
                        reward.intValue("is_get", "isGet") ?: reward.intValue("status") ?: -1
                    ) {
                        1 -> RisingStonesRewardStatus.Received
                        0 -> RisingStonesRewardStatus.Claimable
                        else -> RisingStonesRewardStatus.NotQualified
                    },
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }
        return RisingStonesSignInSummary(
            signInCount = logData?.intValue("count"),
            signInLogs = logs,
            rewards = rewards,
        )
    }

    override suspend fun fetchDashboard(): RisingStonesAccountDashboard {
        val userData = perform(
            path = "api/home/userInfo/getUserInfo",
            capability = RisingStonesCapability.AccountRead,
        ).objectValue("data") ?: throw RisingStonesAccountException.MissingPayload
        val characterObject = userData.objectValue("characterDetail")
            ?: userData.objectValue("character")
            ?: userData.firstNestedObject { it.risingStonesCharacter() != null }
        val summary = fetchSignInSummary()
        return RisingStonesAccountDashboard(
            character = characterObject?.risingStonesCharacter(),
            houseRemainDayText = characterObject?.firstNestedString(HouseRemainKeys)
                ?: userData.firstNestedString(HouseRemainKeys),
            signInCount = summary.signInCount,
            signInLogs = summary.signInLogs,
            rewards = summary.rewards,
        )
    }

    override suspend fun signIn(): RisingStonesDailySignInResult {
        if (RisingStonesCapability.DailySignIn in sessionProvider.capabilities &&
            sessionProvider is RisingStonesExplicitCapabilityProvider
        ) return verifyAction("api/home/sign/signIn", ByteArray(0), setOf(10001), ::signInResult)
        val envelope = perform(
            path = "api/home/sign/signIn",
            capability = RisingStonesCapability.DailySignIn,
            method = RisingStonesHttpMethod.Post,
            acceptedCodes = setOf(10001),
            body = ByteArray(0),
            contentType = FormContentType,
        )
        return signInResult(envelope)
    }

    private fun signInResult(envelope: JsonObject): RisingStonesDailySignInResult {
        val code = envelope.intValue("code")
        val message = envelope.stringValue("msg", "message")
        val data = envelope.objectValue("data")
        return RisingStonesDailySignInResult(
            message = data?.stringValue("sqMsg")?.takeIf(String::isNotBlank)
                ?: message?.takeIf(String::isNotBlank) ?: "OK",
            isAlreadyCheckedIn = code == 10001,
            continuousDays = data?.intValue("continuousDays"),
            totalDays = data?.intValue("totalDays"),
            communityExperience = data?.intValue("sqExp"),
            shopExperience = data?.intValue("shopExp"),
        )
    }

    override suspend fun claimReward(id: Int): String {
        if (RisingStonesCapability.DailySignIn in sessionProvider.capabilities &&
            sessionProvider is RisingStonesExplicitCapabilityProvider
        ) return verifyClaimReward(id)
        val envelope = perform(
            path = "api/home/sign/getSignReward",
            capability = RisingStonesCapability.DailySignIn,
            method = RisingStonesHttpMethod.Post,
            body = formBody("id" to id.toString(), "month" to signInMonth()),
            contentType = FormContentType,
        )
        return envelope.stringValue("msg", "message") ?: "OK"
    }

    override suspend fun verifyDailySignIn(): RisingStonesDailySignInResult =
        verifyAction("api/home/sign/signIn", ByteArray(0)) { envelope ->
            val data = envelope.objectValue("data")
                ?: throw RisingStonesAccountException.MissingPayload
            if (data.intValue("totalDays")?.let { it >= 0 } != true) {
                throw RisingStonesAccountException.MissingPayload
            }
            signInResult(envelope)
        }

    override suspend fun verifyClaimReward(id: Int): String {
        require(id > 0) { "Invalid Rising Stones reward" }
        return verifyAction("api/home/sign/getSignReward",
            formBody("id" to id.toString(), "month" to signInMonth())) { envelope ->
            // This action's official consumer uses the accepted status, not a required data object.
            envelope.stringValue("msg", "message")?.takeIf(String::isNotBlank) ?: "OK"
        }
    }

    private suspend fun <T> verifyAction(
        path: String,
        body: ByteArray,
        acceptedCodes: Set<Int> = emptySet(),
        map: (JsonObject) -> T,
    ): T {
        val context = RisingStonesRequestContext(path, RisingStonesAuthenticationRequirement.Required,
            RisingStonesCapability.DailySignIn)
        val attempt = (sessionProvider as? RisingStonesExplicitCapabilityProvider)
            ?.beginCapabilityAttempt(context) ?: throw RisingStonesAccountException.AuthenticationRequired
        try {
            val response = client.execute(RisingStonesApiRequest(path,
                method = RisingStonesHttpMethod.Post,
                headers = attempt.authorizer.headers(path, RisingStonesCapability.DailySignIn),
                body = body, contentType = FormContentType))
            if (response.statusCode !in 200..299) {
                throw RisingStonesHttpException.ServerResponse(response.statusCode, response.body)
            }
            val envelope = try {
                json.parseToJsonElement(response.body.decodeToString()).jsonObject
            } catch (_: IllegalArgumentException) {
                throw RisingStonesAccountException.MissingPayload
            }
            if (!RisingStonesResponsePolicy.accepts(envelope.intValue("code")) &&
                envelope.intValue("code") !in acceptedCodes
            ) {
                throw RisingStonesAccountException.Business(envelope.intValue("code"),
                    envelope.stringValue("msg", "message"))
            }
            val result = map(envelope)
            if (!attempt.complete()) throw RisingStonesAccountException.AuthenticationRequired
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isHttpAuthenticationFailure() ||
                (error is RisingStonesAccountException.Business && error.code in setOf(401, 403, 10003, 10004, 10005, 10403))
            ) {
                // Revalidate only reads. A rejected write is never automatically replayed.
                revalidateWithoutReplay()
            }
            throw error
        } finally {
            attempt.close()
        }
    }

    private suspend fun perform(
        path: String,
        capability: RisingStonesCapability,
        method: RisingStonesHttpMethod = RisingStonesHttpMethod.Get,
        query: List<RisingStonesApiQueryItem> = emptyList(),
        body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        acceptedCodes: Set<Int> = emptySet(),
    ): JsonObject {
        if (capability !in sessionProvider.capabilities) {
            throw RisingStonesAccountException.AuthenticationRequired
        }
        val initialAuthorizer = sessionProvider.currentAuthorizer()
            ?: throw RisingStonesAccountException.AuthenticationRequired
        suspend fun execute(authorizer: RisingStonesRequestAuthorizer): JsonObject {
            val headers = authorizer.headers(path, capability)
            return json.parseToJsonElement(
                client.execute(
                    RisingStonesApiRequest(
                        path = path,
                        method = method,
                        query = query,
                        headers = headers,
                        body = body,
                        contentType = contentType,
                    ),
                ).body.decodeToString(),
            ).jsonObject
        }
        if (method != RisingStonesHttpMethod.Get && method != RisingStonesHttpMethod.Head) {
            // Legacy providers retain their public contract but never replay an explicit write.
            val response = try {
                execute(initialAuthorizer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.isHttpAuthenticationFailure()) revalidateWithoutReplay()
                throw error
            }
            val code = response.intValue("code")
            if (RisingStonesResponsePolicy.accepts(code) || code in acceptedCodes) return response
            if (code != IdentityConflictCode && response.isAuthenticationFailure()) revalidateWithoutReplay()
            throw RisingStonesAccountException.Business(code, response.stringValue("msg", "message"))
        }
        var response = try {
            execute(initialAuthorizer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            when {
                error.isHttpIdentityConflict() -> execute(
                    (sessionProvider as? RisingStonesIdentityConflictResolver)
                        ?.awaitIdentityConflictResolution() ?: throw error,
                )
                error.isHttpAuthenticationFailure() -> execute(
                    sessionProvider.refreshAuthorizer() ?: throw error,
                )
                else -> throw error
            }
        }
        if (RisingStonesResponsePolicy.accepts(response.intValue("code")) ||
            response.intValue("code") in acceptedCodes
        ) return response
        if (response.intValue("code") == IdentityConflictCode) {
            (sessionProvider as? RisingStonesIdentityConflictResolver)
                ?.awaitIdentityConflictResolution()
                ?.let { response = execute(it) }
        } else if (response.isAuthenticationFailure()) {
            sessionProvider.refreshAuthorizer()?.let { response = execute(it) }
        }
        val code = response.intValue("code")
        if (!RisingStonesResponsePolicy.accepts(code) && code !in acceptedCodes) {
            throw RisingStonesAccountException.Business(
                code = code,
                serverMessage = response.stringValue("msg", "message"),
            )
        }
        return response
    }

    private suspend fun revalidateWithoutReplay() {
        try { sessionProvider.refreshAuthorizer() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the action failure without repeating the write. */ }
    }

    private companion object {
        const val IdentityConflictCode = 10105
        const val FormContentType = "application/x-www-form-urlencoded; charset=utf-8"
        val HouseRemainKeys = setOf(
            "house_remain_day",
            "remain_day",
            "houseRemainDay",
        )
    }
}

private suspend fun RisingStonesRequestAuthorizer.headers(
    path: String,
    capability: RisingStonesCapability,
): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    authorize(
        RisingStonesRequestContext(
            path = path,
            requirement = RisingStonesAuthenticationRequirement.Required,
            capability = capability,
        ),
        RisingStonesHeaderSink { name, value -> headers[name] = value },
    )
    return headers
}

private fun q(name: String, value: Any?) =
    RisingStonesApiQueryItem(name, value?.toString().orEmpty())

private fun formBody(vararg fields: Pair<String, String>): ByteArray =
    fields.joinToString("&") { (name, value) ->
        "${name.formEncoded()}=${value.formEncoded()}"
    }.encodeToByteArray()

private fun String.formEncoded(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun JsonObject.element(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { this[it]?.takeUnless { value -> value is JsonNull } }

private fun JsonObject.stringValue(vararg names: String): String? =
    (element(*names) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.intValue(vararg names: String): Int? {
    val primitive = element(*names) as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
}

private fun JsonObject.objectValue(vararg names: String): JsonObject? =
    element(*names) as? JsonObject

private fun JsonElement?.dictionaryRows(): List<JsonObject> = when (this) {
    is JsonArray -> flatMap { it.dictionaryRows() }
    is JsonObject -> if (values.none { it is JsonArray || it is JsonObject }) {
        listOf(this)
    } else {
        values.flatMap { it.dictionaryRows() }
    }
    else -> emptyList()
}

private fun JsonObject.risingStonesCharacter(): RisingStonesCharacter? {
    val name = stringValue("character_name", "characterName") ?: return null
    return RisingStonesCharacter(
        areaName = stringValue("area_name", "areaName").orEmpty(),
        groupName = stringValue("group_name", "groupName").orEmpty(),
        characterName = name,
    )
}

private fun JsonObject.firstNestedObject(
    predicate: (JsonObject) -> Boolean,
): JsonObject? {
    if (predicate(this)) return this
    values.forEach { value ->
        when (value) {
            is JsonObject -> value.firstNestedObject(predicate)?.let { return it }
            is JsonArray -> value.forEach { child ->
                (child as? JsonObject)?.firstNestedObject(predicate)?.let { return it }
            }
            else -> Unit
        }
    }
    return null
}

private fun JsonObject.firstNestedString(keys: Set<String>): String? {
    entries.forEach { (key, value) ->
        if (key in keys) {
            (value as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { return it }
        }
    }
    values.forEach { value ->
        when (value) {
            is JsonObject -> value.firstNestedString(keys)?.let { return it }
            is JsonArray -> value.forEach { child ->
                (child as? JsonObject)?.firstNestedString(keys)?.let { return it }
            }
            else -> Unit
        }
    }
    return null
}

private fun defaultRewardDescription(id: Int): String = when (id) {
    1 -> "[10 days] Aetheryte Ticket x30"
    2 -> "[20 days] Silver Chocobo Feather x5"
    3 -> "[30 days] Gold Chocobo Feather x1"
    else -> "Reward $id"
}

private fun JsonObject.isAuthenticationFailure(): Boolean {
    val code = intValue("code")
    if (RisingStonesResponsePolicy.accepts(code) || code == 10105) return false
    if (code in setOf(401, 403, 10003, 10004, 10005, 10403)) return true
    val message = stringValue("msg", "message").orEmpty().lowercase()
    return listOf(
        "未登录",
        "登录失效",
        "登录过期",
        "token失效",
        "unauthorized",
        "session expired",
    ).any(message::contains)
}

private fun Throwable.isHttpAuthenticationFailure(): Boolean = causeChain().any {
    it is RisingStonesHttpException.ServerResponse && it.statusCode in setOf(401, 403)
}

private fun Throwable.isHttpIdentityConflict(): Boolean = causeChain().any { cause ->
    cause is RisingStonesHttpException.ServerResponse && runCatching {
        Json.parseToJsonElement(cause.responseBody.decodeToString())
            .jsonObject
            .intValue("code") == 10105
    }.getOrDefault(false)
}

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause }
