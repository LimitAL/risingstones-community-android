package top.cxmeow.risingstones.feature.glamour.data

import kotlinx.coroutines.runBlocking
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

class RisingStonesGlamourSessionValidatorTest {
    @Test
    fun alternateAcceptedProbeGrantsOnlyItsOwnCapability() = runBlocking {
        val transport = GlamourValidationTransport(
            """{"code":10002,"msg":"未登录","data":{"rows":[],"count":0}}""",
        )
        val result = validator(transport).validateSession(RisingStonesRequestAuthorizer { _, _ -> })
        assertEquals(setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.GlamourAuthenticated),
            result.capabilities)
        assertEquals(1, transport.requestCount)
    }

    @Test
    fun alternateAcceptedProbeWithoutRequiredDataDoesNotGrantCapability() = runBlocking {
        for (payload in listOf("", ",\"data\":null", ",\"data\":[]")) {
            val transport = GlamourValidationTransport("""{"code":10002$payload}""")
            val result = validator(transport).validateSession(RisingStonesRequestAuthorizer { _, _ -> })
            assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
            assertEquals(1, transport.requestCount)
        }
    }

    @Test
    fun capabilityIsGrantedOnlyAfterTheReadProbeSucceeds() = runBlocking {
        val transport = GlamourValidationTransport(
            """{"code":10000,"data":{"rows":[],"count":0}}""",
        )
        var context: RisingStonesRequestContext? = null
        val validator = validator(transport)

        val result = validator.validateSession(
            RisingStonesRequestAuthorizer { requestContext, sink ->
                context = requestContext
                sink.set("Cookie", "ff14risingstones=secret")
            },
        )

        assertEquals(
            setOf(
                RisingStonesCapability.AccountRead,
                RisingStonesCapability.GlamourAuthenticated,
            ),
            result.capabilities,
        )
        assertEquals(RisingStonesCapability.GlamourAuthenticated, context?.capability)
        assertEquals(RisingStonesAuthenticationRequirement.Required, context?.requirement)
        assertEquals("api/home/glamour/glamoursList", context?.path)
        assertTrue(transport.lastRequest?.url?.contains("limit=1") == true)
        assertTrue(
            transport.lastRequest?.headers
                ?.entries
                ?.any { it.key.equals("Cookie", ignoreCase = true) } == true,
        )
    }

    @Test
    fun failedOptionalProbePreservesTheOtherwiseValidSession() = runBlocking {
        val validator = validator(
            GlamourValidationTransport("""{"code":10403,"msg":"请先登录","data":[]}"""),
        )

        val result = validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
        assertEquals("Meteor", result.displayName)
    }

    @Test
    fun transportFailureDoesNotRejectTheBaseSession() = runBlocking {
        val validator = validator(GlamourValidationTransport(error = IllegalStateException("down")))

        val result = validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
    }

    private fun validator(transport: GlamourValidationTransport) =
        RisingStonesGlamourSessionValidator(
            baseValidator = RisingStonesSessionValidator {
                RisingStonesSessionValidation(
                    displayName = "Meteor",
                    code = 10000,
                    capabilities = setOf(RisingStonesCapability.AccountRead),
                )
            },
            client = RisingStonesPublicApiClient(
                transport = transport,
                baseUrls = listOf("https://rising.test"),
            ),
            temporarySessionId = "probe-session",
        )
}

private class GlamourValidationTransport(
    private val responseBody: String? = null,
    private val error: Exception? = null,
) : RisingStonesHttpClient {
    var lastRequest: RisingStonesHttpRequest? = null
    var requestCount = 0

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requestCount++
        lastRequest = request
        error?.let { throw it }
        return RisingStonesHttpResponse(
            statusCode = 200,
            headers = emptyMap(),
            body = requireNotNull(responseBody).encodeToByteArray(),
        )
    }
}
