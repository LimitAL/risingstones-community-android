package top.cxmeow.risingstones.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RisingStonesAuthenticationTest {
    @Test
    fun activeSessionOnlySupportsExplicitlyVerifiedCapabilities() {
        val state = RisingStonesSessionState.Active(
            source = RisingStonesCredentialSource.WebCookie,
            capabilities = setOf(RisingStonesCapability.AccountRead),
        )

        assertTrue(state.supports(RisingStonesCapability.AccountRead))
        assertFalse(state.supports(RisingStonesCapability.ForumWrite))
    }

    @Test
    fun signedOutSessionSupportsNoAuthenticatedCapability() {
        assertFalse(RisingStonesSessionState.SignedOut.supports(RisingStonesCapability.AccountRead))
    }
}

