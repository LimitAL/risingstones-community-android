package top.cxmeow.risingstones.app

import androidx.lifecycle.ViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCredentialSource
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

class SessionFeatureViewModelStoreOwnerTest {
    @Test fun credentialRevisionClearsEvenWhenIntermediateValidatingStateWasConflated() {
        val owner = SessionFeatureViewModelStoreOwner()
        val active = RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
            setOf(RisingStonesCapability.AccountRead))
        owner.synchronize(active, 1L)
        val model = TrackedModel()
        owner.viewModelStore.put("account", model)
        val before = owner.revision.value
        owner.synchronize(active, 2L)
        assertTrue(model.cleared)
        assertEquals(before + 1, owner.revision.value)
    }

    @Test fun sameCredentialWriteGrantDoesNotDestroyTheSubmittingModel() {
        val owner = SessionFeatureViewModelStoreOwner()
        val active = RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
            setOf(RisingStonesCapability.AccountRead))
        owner.synchronize(active, 1L)
        val model = TrackedModel()
        owner.viewModelStore.put("account", model)
        owner.synchronize(active.copy(capabilities = active.capabilities + RisingStonesCapability.DailySignIn), 1L)
        assertFalse(model.cleared)
        assertEquals(0L, owner.revision.value)
    }

    @Test fun revisionRecreatesVisibleForumOnlyWhenProtectedModelsAreCleared() {
        val owner = SessionFeatureViewModelStoreOwner()
        val active = RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
            setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.ForumWrite))
        owner.synchronize(active)
        val initialRevision = owner.revision.value
        owner.synchronize(active)
        assertEquals(initialRevision, owner.revision.value)
        owner.synchronize(active.copy(capabilities = setOf(RisingStonesCapability.AccountRead)))
        assertEquals(initialRevision + 1, owner.revision.value)
        owner.synchronize(RisingStonesSessionState.SignedOut)
        assertEquals(initialRevision + 2, owner.revision.value)
    }

    @Test fun activeSessionRefreshKeepsFeatureSelection() {
        val owner = SessionFeatureViewModelStoreOwner()
        val model = TrackedModel()
        owner.viewModelStore.put("feature", model)
        owner.synchronize(RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
            setOf(RisingStonesCapability.AccountRead)))
        assertFalse(model.cleared)
    }

    @Test fun invalidOrChangingCredentialsClearAllProtectedFeatureModels() {
        listOf(RisingStonesSessionState.SignedOut, RisingStonesSessionState.Restoring,
            RisingStonesSessionState.Validating, RisingStonesSessionState.Invalid(null)).forEach { state ->
            val owner = SessionFeatureViewModelStoreOwner()
            val model = TrackedModel()
            owner.viewModelStore.put("feature", model)
            owner.synchronize(state)
            assertTrue(model.cleared)
            assertTrue(owner.viewModelStore.keys().isEmpty())
        }
    }

    @Test fun revokedCapabilityOrChangedSourceClearsProtectedModelsEvenWhileActive() {
        listOf(
            RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie, setOf(RisingStonesCapability.AccountRead)),
            RisingStonesSessionState.Active(RisingStonesCredentialSource.HostProvided,
                setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.PersonalData)),
        ).forEach { next ->
            val owner = SessionFeatureViewModelStoreOwner()
            owner.synchronize(RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
                setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.PersonalData)))
            val model = TrackedModel()
            owner.viewModelStore.put("exploration", model)
            owner.synchronize(next)
            assertTrue(model.cleared)
            assertTrue(owner.viewModelStore.keys().isEmpty())
        }
    }

    @Test fun AddingOrRevalidatingCapabilitiesPreservesSelection() {
        val owner = SessionFeatureViewModelStoreOwner()
        owner.synchronize(RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
            setOf(RisingStonesCapability.AccountRead)))
        val model = TrackedModel()
        owner.viewModelStore.put("feature", model)
        repeat(2) {
            owner.synchronize(RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie,
                setOf(RisingStonesCapability.AccountRead, RisingStonesCapability.PersonalData)))
        }
        assertFalse(model.cleared)
    }
}

private class TrackedModel : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
}
