package top.cxmeow.risingstones.feature.guild.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.guild.domain.GuildException
import top.cxmeow.risingstones.feature.guild.domain.GuildHousingVisibility
import top.cxmeow.risingstones.feature.guild.domain.GuildId
import top.cxmeow.risingstones.feature.guild.domain.GuildMemberRegistration
import top.cxmeow.risingstones.feature.guild.domain.OwnGuild
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class GuildMemberOptionalGroupsTest {
 @Test fun sourceRowsDistinguishMissingFromEmptyAndRetainDuplicates() = runTest {
  val missing=service("{}").members(GuildId("123"))
  val empty=service("""{"registered":[],"unRegister":[]}""").members(GuildId("123"))
  assertNull(missing.sourceRows!!.registered);assertEquals(0,empty.sourceRows!!.registered);assertFalse(missing==empty)
  val rows=service("""{"registered":[{"uuid":"mock"},{"uuid":"mock"}]}""").members(GuildId("123"))
  assertEquals(2,rows.sourceRows!!.registered);assertNull(rows.sourceRows!!.unregistered);assertEquals(2,rows.registered.size)
  assertEquals(rows.sourceRows,rows.copy(emptyList(),emptyList()).sourceRows)
  assertEquals(rows,top.cxmeow.risingstones.feature.guild.domain.GuildMembers.`copy$default`(rows,null,null,3,null))
 }

 private fun service(body: String): GuildApiService {
  val transport=OptionalGroupsTransport().apply { this.body=envelope(body) }
  return GuildApiService(RisingStonesPublicApiClient(transport,listOf("https://rising.test")),OptionalGroupsProvider())
 }
 @Test fun missingAndNullGroupsPreserveTheOtherGroup() = runTest {
  for (body in listOf("""{"registered":[{"uuid":"mock"}]}""", """{"registered":[{"uuid":"mock"}],"unRegister":null}""")) {
   val result=service(body).members(GuildId("123"));assertEquals("mock",result.registered.single().authorUuid);assertTrue(result.unregistered.isEmpty())
  }
  for (body in listOf("""{"unRegister":[{"character_name":"Mock"}]}""", """{"registered":null,"unRegister":[{"character_name":"Mock"}]}""")) {
   val result=service(body).members(GuildId("123"));assertEquals("Mock",result.unregistered.single().characterName);assertTrue(result.registered.isEmpty())
  }
  assertTrue(service("{}").members(GuildId("123")).registered.isEmpty())
 }
 @Test fun malformedPresentGroupsAndRowsRemainErrors() = runTest {
  for (value in listOf("{}","1","true","\"bad\"","[null]","[1]")) {
   for (key in listOf("registered","unRegister")) {
    try { service("{\"registered\":[],\"unRegister\":[],\"$key\":$value}").members(GuildId("123"));fail(value) }
    catch (_:GuildException.InvalidResponse) {}
   }
  }
 }
}
private class OptionalGroupsProvider : RisingStonesSessionProvider {
    override var capabilities: Set<RisingStonesCapability> = setOf(RisingStonesCapability.GuildRead)
    var refreshes = 0
    var revokeOnRefresh = false
    val contexts = mutableListOf<RisingStonesRequestContext>()
    private val authorizer = RisingStonesRequestAuthorizer { context, sink ->
        contexts += context
        sink.set("X-Test-Authorization", "fixture")
    }

    override suspend fun currentAuthorizer(): RisingStonesRequestAuthorizer = authorizer

    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshes++
        if (revokeOnRefresh) capabilities = emptySet()
        return authorizer
    }
}

private class OptionalGroupsTransport : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    var body = envelope("""{"gc_id":"0"}""")
    var statusCode = 200
    var cancel = false
    var entered: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null
    var ignoreCancellation = false
    var responder: ((RisingStonesHttpRequest) -> String)? = null

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        if (cancel) throw CancellationException()
        requests += request
        entered?.complete(Unit)
        try {
            release?.await()
        } catch (error: CancellationException) {
            if (!ignoreCancellation) throw error
            withContext(NonCancellable) { release?.await() }
        }
        return RisingStonesHttpResponse(
            statusCode,
            emptyMap(),
            (responder?.invoke(request) ?: body).encodeToByteArray(),
        )
    }
}

private fun envelope(data: String, code: Int = 10000): String =
    """{"code":$code,"msg":"fixture","data":$data}"""

