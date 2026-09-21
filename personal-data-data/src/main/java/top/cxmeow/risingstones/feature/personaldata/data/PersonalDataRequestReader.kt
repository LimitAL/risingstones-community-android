package top.cxmeow.risingstones.feature.personaldata.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.network.*

/** One recovery budget for an entire read, including HTTP and envelope failures. */
internal class PersonalDataRequestReader(
    private val client: RisingStonesPublicApiClient,
    private val session: RisingStonesSessionProvider,
    private val json: Json,
    private val temporarySessionId: String,
) {
    suspend fun read(path: String, query: List<RisingStonesApiQueryItem>): JsonObject {
        requireAccess()
        var authorizer = authorizeSafely { session.currentAuthorizer() }
        requireAccess()
        if (authorizer == null) throw PersonalDataException.AuthenticationRequired
        repeat(2) { attempt ->
            val response = try {
                execute(requireNotNull(authorizer), path, query)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: RecoveryRequired) {
                if (attempt != 0) throw PersonalDataException.AuthenticationRequired
                authorizer = recover(failure.conflict)
                return@repeat
            }
            val code = response.number("code") ?: throw PersonalDataException.MissingPayload
            if (RisingStonesResponsePolicy.accepts(code)) return response
            val conflict = code == 10105
            if (conflict || response.requiresAuthentication()) {
                if (attempt != 0) throw PersonalDataException.AuthenticationRequired
                authorizer = recover(conflict)
            } else {
                // The official error text can contain personal data; it is not a public UI message.
                throw PersonalDataException.Business(code, null)
            }
        }
        throw PersonalDataException.AuthenticationRequired
    }

    private suspend fun recover(conflict: Boolean): RisingStonesRequestAuthorizer {
        requireAccess()
        val next = authorizeSafely { if (conflict) {
            (session as? RisingStonesIdentityConflictResolver)?.awaitIdentityConflictResolution()
        } else session.refreshAuthorizer() }
        requireAccess()
        return next ?: throw PersonalDataException.AuthenticationRequired
    }

    private suspend fun execute(
        authorizer: RisingStonesRequestAuthorizer,
        path: String,
        query: List<RisingStonesApiQueryItem>,
    ): JsonObject {
        requireAccess()
        val headers = linkedMapOf<String, String>()
        authorizeSafely { authorizer.authorize(
            RisingStonesRequestContext(path, RisingStonesAuthenticationRequirement.Required,
                RisingStonesCapability.PersonalData),
            RisingStonesHeaderSink(headers::set),
        ) }
        requireAccess()
        if (headers.isEmpty()) throw PersonalDataException.AuthenticationRequired
        val response = try {
            client.execute(RisingStonesApiRequest(path,
                query = query + RisingStonesApiQueryItem("tempsuid", temporarySessionId), headers = headers))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            requireAccess()
            val http = failure.httpFailure()
            if (http != null) {
                checkStatus(http.statusCode, http.responseBody)
            }
            throw IOException("Personal data request failed")
        }
        requireAccess()
        checkStatus(response.statusCode, response.body)
        return parse(response.body) ?: throw PersonalDataException.MissingPayload
    }

    private fun checkStatus(status: Int, body: ByteArray) {
        if (status in 200..299) return
        val envelope = parse(body)
        when {
            envelope?.number("code") == 10105 -> throw RecoveryRequired(conflict = true)
            status in listOf(401, 403) || envelope?.requiresAuthentication() == true ->
                throw RecoveryRequired(conflict = false)
            else -> throw IOException("Personal data request failed")
        }
    }

    private fun parse(bytes: ByteArray): JsonObject? = try {
        json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) { null }

    private suspend fun <T> authorizeSafely(block: suspend () -> T): T {
        requireAccess()
        return try {
            block().also { requireAccess() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            requireAccess()
            val http = failure.httpFailure()
            val envelope = http?.let { parse(it.responseBody) }
            if (failure is PersonalDataException.AuthenticationRequired ||
                http?.statusCode in listOf(401, 403) || envelope?.number("code") == 10105 ||
                envelope?.requiresAuthentication() == true ||
                failure is PersonalDataException.Business && failure.code in AuthenticationCodes + 10105
            ) throw PersonalDataException.AuthenticationRequired
            if (failure is PersonalDataException.Business) throw PersonalDataException.Business(failure.code, null)
            throw IOException("Personal data authorization failed")
        }
    }

    private suspend fun requireAccess() {
        currentCoroutineContext().ensureActive()
        if (RisingStonesCapability.PersonalData !in session.capabilities) {
            throw PersonalDataException.AuthenticationRequired
        }
    }
}

private class RecoveryRequired(val conflict: Boolean) : Exception()
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
private fun JsonObject.requiresAuthentication(): Boolean {
    val code = number("code")
    if (RisingStonesResponsePolicy.accepts(code) || code == 10105) return false
    if (code in AuthenticationCodes) return true
    val message = listOf("msg", "message").firstNotNullOfOrNull {
        (this[it] as? JsonPrimitive)?.contentOrNull
    }.orEmpty().lowercase()
    return listOf("未登录", "登录失效", "登录过期", "token失效", "unauthorized", "session expired")
        .any(message::contains)
}
private val AuthenticationCodes = setOf(401, 403, 10001, 10003, 10004, 10005, 10403)
private fun Throwable.httpFailure(): RisingStonesHttpException.ServerResponse? {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is RisingStonesHttpException.ServerResponse) return current
        current = current.cause
    }
    return null
}
