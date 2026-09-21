package top.cxmeow.risingstones.feature.personaldata.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesPersonalDataSessionValidatorTest {
    @Test
    fun accepted10002RootProbesAddOnlyPersonalDataDespiteAuthenticationMessage() = runBlocking {
        val transport = PersonalDataValidationTransport(
            identityPayload = """{"code":10002,"msg":"未登录","data":{"character_name":"Fixture Hero"}}""",
            availabilityPayload = """{"code":10002,"msg":"登录失效","data":{"pvp":"1"}}""",
        )

        val result = validator(transport).validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GlamourAuthenticated,
                RisingStonesCapability.PersonalData),
            result.capabilities,
        )
        assertEquals("Meteor", result.displayName)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun acceptedCodesStillRequireObjectsFromBothRootProbes() = runBlocking {
        for (code in listOf(10000, 10002)) {
            for (data in listOf("", ",\"data\":null", ",\"data\":[]")) {
                for (invalidIdentity in listOf(true, false)) {
                    val invalid = """{"code":$code$data}"""
                    val valid = """{"code":$code,"data":{}}"""
                    val transport = PersonalDataValidationTransport(
                        identityPayload = if (invalidIdentity) invalid else valid,
                        availabilityPayload = if (invalidIdentity) valid else invalid,
                    )

                    val result = validator(transport).validateSession(RisingStonesRequestAuthorizer { _, _ -> })

                    assertEquals(
                        setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GlamourAuthenticated),
                        result.capabilities,
                    )
                    assertEquals("Meteor", result.displayName)
                    assertEquals(2, transport.requests.size)
                }
            }
        }
    }

    @Test
    fun capabilityIsGrantedOnlyAfterBothReadOnlyRootProbesSucceed() = runBlocking {
        val transport = PersonalDataValidationTransport()
        val contexts = mutableListOf<RisingStonesRequestContext>()

        val result = validator(transport).validateSession(
            RisingStonesRequestAuthorizer { context, sink ->
                contexts += context
                sink.set("Cookie", "ff14risingstones=secret")
            },
        )

        assertEquals(
            setOf(
                RisingStonesCapability.AccountRead,
                RisingStonesCapability.GlamourAuthenticated,
                RisingStonesCapability.PersonalData,
            ),
            result.capabilities,
        )
        assertEquals(2, contexts.size)
        assertTrue(contexts.all { it.capability == RisingStonesCapability.PersonalData })
        assertTrue(contexts.all {
            it.requirement == RisingStonesAuthenticationRequirement.Required
        })
        assertEquals(
            listOf(
                "/api/home/groupAndRole/getCharacterBindInfo",
                "/api/home/dataCenter/dataOpenStatus",
            ),
            transport.requests.map { it.url.toHttpUrl().encodedPath },
        )
        assertTrue(transport.requests.all {
            it.url.toHttpUrl().queryParameter("tempsuid") == "probe-session"
        })
        assertEquals("2", transport.requests.first().url.toHttpUrl().queryParameter("platform"))
        assertTrue(transport.requests.all { request ->
            request.headers.keys.any { it.equals("Cookie", ignoreCase = true) }
        })
    }

    @Test
    fun unavailablePersonalDataPreservesAllBaseCapabilities() = runBlocking {
        val transport = PersonalDataValidationTransport(
            availabilityPayload = """{"code":10403,"msg":"请先登录","data":[]}""",
        )

        val result = validator(transport).validateSession(
            RisingStonesRequestAuthorizer { _, _ -> },
        )

        assertEquals(
            setOf(
                RisingStonesCapability.AccountRead,
                RisingStonesCapability.GlamourAuthenticated,
            ),
            result.capabilities,
        )
        assertEquals("Meteor", result.displayName)
    }

    @Test
    fun transportFailureDoesNotRejectTheBaseSession() = runBlocking {
        val result = validator(
            PersonalDataValidationTransport(error = IllegalStateException("down")),
        ).validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(
            setOf(
                RisingStonesCapability.AccountRead,
                RisingStonesCapability.GlamourAuthenticated,
            ),
            result.capabilities,
        )
    }

    private fun validator(transport: PersonalDataValidationTransport) =
        RisingStonesPersonalDataSessionValidator(
            baseValidator = RisingStonesSessionValidator {
                RisingStonesSessionValidation(
                    displayName = "Meteor",
                    code = 10000,
                    capabilities = setOf(
                        RisingStonesCapability.AccountRead,
                        RisingStonesCapability.GlamourAuthenticated,
                    ),
                )
            },
            client = RisingStonesPublicApiClient(
                transport = transport,
                baseUrls = listOf("https://rising.test"),
            ),
            temporarySessionId = "probe-session",
        )
}

private class PersonalDataValidationTransport(
    private val identityPayload: String =
        """{"code":10000,"data":{"character_name":"Meteor"}}""",
    private val availabilityPayload: String =
        """{"code":10000,"data":{"pvp":"1","jue7":"1"}}""",
    private val error: Exception? = null,
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        error?.let { throw it }
        val body = if (
            request.url.toHttpUrl().encodedPath.endsWith("getCharacterBindInfo")
        ) {
            identityPayload
        } else {
            availabilityPayload
        }
        return RisingStonesHttpResponse(
            statusCode = 200,
            headers = emptyMap(),
            body = body.encodeToByteArray(),
        )
    }
}
