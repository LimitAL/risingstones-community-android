package top.cxmeow.risingstones.feature.recruitment.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesAuthenticationRequirement
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesRequestAuthorizer
import top.cxmeow.risingstones.core.auth.RisingStonesRequestContext
import top.cxmeow.risingstones.network.RisingStonesHttpClient
import top.cxmeow.risingstones.network.RisingStonesHttpMethod
import top.cxmeow.risingstones.network.RisingStonesHttpRequest
import top.cxmeow.risingstones.network.RisingStonesHttpResponse
import top.cxmeow.risingstones.network.RisingStonesPublicApiClient
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesRecruitmentSessionValidatorTest {
    @Test
    fun acceptedListShapeGrantsOnlyReadCapabilityAndUsesReviewedGet() = runBlocking {
        for ((code, rows) in listOf(10000 to "[]", 10002 to "[{}]")) {
            var context: RisingStonesRequestContext? = null
            val transport = RecruitmentValidationTransport(
                responseBody = """{"code":$code,"data":{"rows":$rows}}""",
            )
            val validator = validator(
                transport = transport,
                baseCapabilities = setOf(RisingStonesCapability.AccountRead),
            )

            val result = validator.validateSession(
                RisingStonesRequestAuthorizer { requestContext, sink ->
                    context = requestContext
                    sink.set("Cookie", "ff14risingstones=secret")
                },
            )

            assertEquals("Base name", result.displayName)
            assertEquals(10000, result.code)
            assertEquals(
                setOf(
                    RisingStonesCapability.AccountRead,
                    RisingStonesCapability.RecruitmentAuthenticated,
                ),
                result.capabilities,
            )
            assertFalse(RisingStonesCapability.RecruitmentWrite in result.capabilities)
            assertEquals(1, transport.requests.size)
            val request = transport.requests.single()
            val url = request.url.toHttpUrl()
            assertEquals("/api/home/recruit/recruitGuildList", url.encodedPath)
            assertEquals("1", url.queryParameter("page"))
            assertEquals("1", url.queryParameter("limit"))
            assertEquals(RisingStonesHttpMethod.Get, request.method)
            assertNull(request.body)
            assertEquals("ff14risingstones=secret", request.headers["Cookie"])
            assertEquals("api/home/recruit/recruitGuildList", context?.path)
            assertEquals(RisingStonesAuthenticationRequirement.Required, context?.requirement)
            assertEquals(RisingStonesCapability.RecruitmentAuthenticated, context?.capability)
        }
    }

    @Test
    fun failedProbeRemovesStaleReadAndPreservesEveryOtherVerifiedCapability() = runBlocking {
        val invalidResponses = listOf(
            """{"code":10403,"data":{"rows":[]}}""",
            """{"code":10000}""",
            """{"code":10000,"data":null}""",
            """{"code":10000,"data":[]}""",
            """{"code":10000,"data":{}}""",
            """{"code":10000,"data":{"rows":{}}}""",
            "not-json",
        )
        val retained = setOf(
            RisingStonesCapability.MessageRead,
            RisingStonesCapability.RecruitmentWrite,
        )

        for (response in invalidResponses) {
            val result = validator(
                transport = RecruitmentValidationTransport(response),
                baseCapabilities = retained + RisingStonesCapability.RecruitmentAuthenticated,
            ).validateSession(RisingStonesRequestAuthorizer { _, _ -> })

            assertEquals(retained, result.capabilities)
        }
    }

    @Test
    fun transportFailureRemovesOnlyStaleReadCapability() = runBlocking {
        val result = validator(
            transport = RecruitmentValidationTransport(failure = IllegalStateException("offline")),
            baseCapabilities = setOf(
                RisingStonesCapability.AccountRead,
                RisingStonesCapability.RecruitmentAuthenticated,
            ),
        ).validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
    }

    @Test
    fun acceptedEnvelopeOnHttpFailureCannotGrantReadCapability() = runBlocking {
        val result = validator(
            transport = RecruitmentValidationTransport(
                responseBody = """{"code":10000,"data":{"rows":[]}}""",
                statusCode = 401,
            ),
            baseCapabilities = setOf(
                RisingStonesCapability.AccountRead,
                RisingStonesCapability.RecruitmentAuthenticated,
            ),
        ).validateSession(RisingStonesRequestAuthorizer { _, _ -> })

        assertEquals(setOf(RisingStonesCapability.AccountRead), result.capabilities)
    }

    @Test
    fun cancellationFromReadProbePropagates() {
        val validator = validator(
            transport = RecruitmentValidationTransport(failure = CancellationException("cancelled")),
            baseCapabilities = setOf(RisingStonesCapability.RecruitmentAuthenticated),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                validator.validateSession(RisingStonesRequestAuthorizer { _, _ -> })
            }
        }
    }

    private fun validator(
        transport: RisingStonesHttpClient,
        baseCapabilities: Set<RisingStonesCapability>,
    ) = RisingStonesRecruitmentSessionValidator(
        baseValidator = RisingStonesSessionValidator {
            RisingStonesSessionValidation(
                displayName = "Base name",
                code = 10000,
                capabilities = baseCapabilities,
            )
        },
        client = RisingStonesPublicApiClient(
            transport = transport,
            baseUrls = listOf("https://rising.test"),
        ),
    )
}

private class RecruitmentValidationTransport(
    private val responseBody: String = "",
    private val failure: Exception? = null,
    private val statusCode: Int = 200,
) : RisingStonesHttpClient {
    val requests = mutableListOf<RisingStonesHttpRequest>()

    override suspend fun execute(request: RisingStonesHttpRequest): RisingStonesHttpResponse {
        requests += request
        failure?.let { throw it }
        return RisingStonesHttpResponse(
            statusCode = statusCode,
            headers = emptyMap(),
            body = responseBody.encodeToByteArray(),
        )
    }
}
