package top.cxmeow.risingstones.auth.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RisingStonesWebLoginControllerTest {
    private val policy = RisingStonesOfficialNavigationPolicy()

    @Test
    fun permitsOnlyHttpsOfficialLoginDomains() {
        assertTrue(policy.allows("https://ff14risingstones.web.sdo.com/mob/index.html"))
        assertTrue(policy.allows("https://daoyu.sdo.com/oauth"))
        assertTrue(policy.allows("https://www.daoyu8.com/login"))
        assertFalse(policy.allows("http://ff14risingstones.web.sdo.com/mob/index.html"))
        assertFalse(policy.allows("https://sdo.com.example.org/login"))
        assertFalse(policy.allows("intent://login"))
    }

    @Test
    fun parsesOnlyExactRisingStonesCookieName() {
        assertEquals(
            "session=value",
            risingStonesCookieValue(
                "other=1; ff14risingstones=session=value; unrelated_session=secret",
            ),
        )
        assertNull(risingStonesCookieValue("ff14risingstones_other=value"))
        assertNull(risingStonesCookieValue("ff14risingstones="))
    }

    @Test
    fun candidateGateDeduplicatesWithinAttemptButAllowsExplicitRetry() {
        val gate = RisingStonesCookieCandidateGate()

        assertTrue(gate.shouldEmit("same-cookie", returnGeneration = 0))
        assertFalse(gate.shouldEmit("same-cookie", returnGeneration = 0))

        gate.startAttempt()

        assertTrue(gate.shouldEmit("same-cookie", returnGeneration = 0))
    }

    @Test
    fun candidateGateAllowsSameCookieAfterAuthenticationReturn() {
        val gate = RisingStonesCookieCandidateGate()

        assertTrue(gate.shouldEmit("same-cookie", returnGeneration = 0))
        assertTrue(gate.shouldEmit("same-cookie", returnGeneration = 1))
        assertFalse(gate.shouldEmit("same-cookie", returnGeneration = 1))
    }

    @Test
    fun navigationTrackerAdvancesOnlyAfterReturningFromAuthenticationHost() {
        val tracker = RisingStonesAuthenticationNavigationTracker()

        tracker.record("https://ff14risingstones.web.sdo.com/mob/index.html")
        assertEquals(0, tracker.returnGeneration)
        tracker.record("https://daoyu.sdo.com/oauth")
        assertEquals(0, tracker.returnGeneration)
        tracker.record("https://ff14risingstones.web.sdo.com/mob/index.html")
        assertEquals(1, tracker.returnGeneration)
        tracker.record("https://ff14risingstones.web.sdo.com/mob/index.html#/index")
        assertEquals(1, tracker.returnGeneration)
    }

    @Test
    fun rejectsLookalikeAndCredentialBearingHosts() {
        assertFalse(policy.allows("https://ff14risingstones.web.sdo.com.evil.example/login"))
        assertFalse(policy.allows("https://www.daoyu8.com@evil.example/login"))
        assertFalse(policy.allows("https://evil.example/www.daoyu8.com"))
    }
}
