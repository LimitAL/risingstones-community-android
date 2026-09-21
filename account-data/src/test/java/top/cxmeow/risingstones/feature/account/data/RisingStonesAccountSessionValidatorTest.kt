package top.cxmeow.risingstones.feature.account.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.network.RisingStonesApiException
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesAccountSessionValidatorTest {
    @Test
    fun accepted10002ProbeAddsOnlyAccountReadAndPreservesBaseCapabilities() = runBlocking {
        val validator = RisingStonesAccountSessionValidator(
            baseValidator = RisingStonesSessionValidator {
                RisingStonesSessionValidation(
                    displayName = "Base name",
                    code = 10002,
                    capabilities = setOf(RisingStonesCapability.GlamourAuthenticated),
                )
            },
            client = RisingStonesPublicApiClient(
                transport = ValidationTransport("""{"code":10002,"msg":"未登录","data":{}}"""),
                baseUrls = listOf("https://rising.test"),
            ),
        )

        val result = validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals("Base name", result.displayName)
        assertEquals(10002, result.code)
        assertEquals(
            setOf(RisingStonesCapability.GlamourAuthenticated, RisingStonesCapability.AccountRead),
            result.capabilities,
        )
    }

    @Test
    fun acceptedCodeWithoutAccountObjectDoesNotGrantReadCapability() {
        for (code in listOf(10000, 10002)) {
            for (data in listOf("", ",\"data\":null", ",\"data\":[]")) {
                val validator = RisingStonesAccountSessionValidator(
                    baseValidator = RisingStonesSessionValidator {
                        RisingStonesSessionValidation(displayName = null, code = 10000)
                    },
                    client = RisingStonesPublicApiClient(
                        transport = ValidationTransport("""{"code":$code$data}"""),
                        baseUrls = listOf("https://rising.test"),
                    ),
                )

                val failure = assertThrows(RisingStonesApiException::class.java) {
                    runBlocking { validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> }) }
                }

                assertEquals(code, failure.code)
            }
        }
    }

    @Test
    fun accountReadIsGrantedOnlyAfterTheOfficialReadEndpointSucceeds() = runBlocking {
        var context: RisingStonesRequestContext? = null
        val validator = RisingStonesAccountSessionValidator(
            baseValidator = RisingStonesSessionValidator {
                RisingStonesSessionValidation(displayName = null, code = 10000)
            },
            client = RisingStonesPublicApiClient(
                transport = ValidationTransport(
                    """{"code":10000,"data":{"characterDetail":{"character_name":"Meteor"}}}""",
                ),
                baseUrls = listOf("https://rising.test"),
            ),
        )

        val result = validator.validateSession(
            RisingStonesRequestAuthorizer { requestContext, sink ->
                context = requestContext
                sink.set("Cookie", "ff14risingstones=secret")
            },
        )

        assertEquals("Meteor", result.displayName)
        assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
        assertEquals(RisingStonesCapability.AccountRead, context?.capability)
        assertEquals(RisingStonesAuthenticationRequirement.Required, context?.requirement)
        assertEquals("api/home/userInfo/getUserInfo", context?.path)
    }

    @Test
    fun sessionIsRejectedWhenAccountReadIsUnavailable() {
        val validator = RisingStonesAccountSessionValidator(
            baseValidator = RisingStonesSessionValidator {
                RisingStonesSessionValidation(displayName = null, code = 10000)
            },
            client = RisingStonesPublicApiClient(
                transport = ValidationTransport("""{"code":10403,"msg":"请先登录","data":[]}"""),
                baseUrls = listOf("https://rising.test"),
            ),
        )

        assertThrows(RisingStonesApiException::class.java) {
            runBlocking {
                validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
            }
        }
    }
}

private class ValidationTransport(
    private val responseBody: String,
) : RisingStonesHttpClient {
    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse =
        RisingStonesHttpResponse(
            statusCode = 200,
            headers = emptyMap(),
            body = responseBody.encodeToByteArray(),
        )
}
