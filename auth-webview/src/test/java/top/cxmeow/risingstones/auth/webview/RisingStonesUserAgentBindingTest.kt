package top.cxmeow.risingstones.auth.webview

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.network.RisingStonesSessionValidation
import top.cxmeow.risingstones.network.RisingStonesSessionValidator

class RisingStonesUserAgentBindingTest {
    @Test fun restoringAndRefreshingNeverSubstituteTheLoginUserAgent() = runTest {
        val captured = RisingStonesCookieCredential("fixture-cookie", "Fixture WebView/100 (login)")
        val store = object : RisingStonesCookieStore {
            override suspend fun read() = captured
            override suspend fun write(credential: RisingStonesCookieCredential) = Unit
            override suspend fun clear() = Unit
        }
        val observedAgents = mutableListOf<String>()
        val validator = RisingStonesSessionValidator { authorizer ->
            assertPair(authorizer, observedAgents)
            RisingStonesSessionValidation(null, 10000, setOf(RisingStonesCapability.AccountRead))
        }
        val session = RisingStonesWebCookieSessionProvider(store, validator)
        session.restore()
        assertPair(requireNotNull(session.currentAuthorizer()), observedAgents)
        assertPair(requireNotNull(session.refreshAuthorizer()), observedAgents)
        assertEquals(List(4) { "Fixture WebView/100 (login)" }, observedAgents)
    }

    private suspend fun assertPair(authorizer: RisingStonesRequestAuthorizer, agents: MutableList<String>) {
        var cookie: String? = null
        var agent: String? = null
        authorizer.authorize(RisingStonesRequestContext("api/home/test", RisingStonesAuthenticationRequirement.Required),
            RisingStonesHeaderSink { name, value ->
                if (name.equals("cookie", true)) cookie = value
                if (name.equals("user-agent", true)) agent = value
            })
        assertEquals("ff14risingstones=fixture-cookie", cookie)
        agents += requireNotNull(agent)
    }
}
