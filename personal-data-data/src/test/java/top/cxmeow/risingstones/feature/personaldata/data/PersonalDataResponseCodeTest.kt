package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesSessionProvider
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient

class PersonalDataResponseCodeTest {
    @Test
    fun acceptedRootReadsWinOverAuthenticationMessagesWithoutRefresh() = runBlocking {
        for (code in listOf(10000, 10002)) {
            val transport = ResponseCodePersonalDataTransport { request ->
                val data = when (request.url.toHttpUrl().pathSegments.last()) {
                    "getCharacterBindInfo" -> """{"character_name":"Fixture Hero","area_name":"Fixture Area"}"""
                    "dataOpenStatus" -> """{"pvp":"1","fishing":"0"}"""
                    else -> error("Unexpected endpoint")
                }
                """{"code":$code,"msg":"未登录","data":$data}"""
            }
            val session = ResponseCodePersonalDataSession()
            val service = service(transport, session)

            assertEquals("Fixture Hero", service.fetchIdentity().characterName)
            val availability = service.fetchAvailability()

            assertEquals(true, availability.hasData(PersonalDataBoard.Frontline))
            assertEquals(false, availability.hasData(PersonalDataBoard.Fishing))
            assertEquals(2, transport.requests.size)
            assertEquals(0, session.refreshes)
        }
    }

    @Test
    fun acceptedRootReadCodesStillRequireObjectPayloads() {
        for (code in listOf(10000, 10002)) {
            for (data in listOf("", ",\"data\":null", ",\"data\":[]")) {
                val transport = ResponseCodePersonalDataTransport { """{"code":$code,"msg":"未登录"$data}""" }
                val session = ResponseCodePersonalDataSession()
                val service = service(transport, session)

                assertThrows(PersonalDataException.MissingPayload::class.java) {
                    runBlocking { service.fetchIdentity() }
                }
                assertThrows(PersonalDataException.MissingPayload::class.java) {
                    runBlocking { service.fetchAvailability() }
                }

                assertEquals(2, transport.requests.size)
                assertEquals(0, session.refreshes)
            }
        }
    }

    private fun service(transport: RisingStonesHttpClient, session: RisingStonesSessionProvider) =
        PersonalDataApiService(RisingStonesPublicApiClient(transport, listOf("https://rising.test")), session)
}

private class ResponseCodePersonalDataSession : RisingStonesSessionProvider {
    override val capabilities = setOf(RisingStonesCapability.PersonalData)
    var refreshes = 0
    override suspend fun currentAuthorizer() = RisingStonesRequestAuthorizer { _, sink -> sink.set("Authorization", "Fixture token") }
    override suspend fun refreshAuthorizer(): RisingStonesRequestAuthorizer {
        refreshes++
        return currentAuthorizer()
    }
}

private class ResponseCodePersonalDataTransport(
    private val response: (RisingStonesHttpRequest) -> String,
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        return RisingStonesHttpResponse(200, emptyMap(), response(request).encodeToByteArray())
    }
}
