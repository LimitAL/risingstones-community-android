package top.cxmeow.risingstones.feature.glamour.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GlamourUiStateLegacyCopyCompatibilityTest {
    @Test fun legacyDefaultConstructionAndUnrelatedCopyKeepOldFieldsAndTypedIdentity() {
        val failure = IllegalStateException()
        val typed = GlamourUiState(page = 7, followError = "legacy", error = "tail", followFailure = failure)
        val copied = typed.copy(page = 9)
        assertEquals(9, copied.page)
        assertEquals("legacy", copied.followError)
        assertEquals("tail", copied.error)
        assertSame(failure, copied.followFailure)
        assertSame(failure, copied.copy().followFailure)
        assertEquals(1, GlamourUiState().page)
        assertNull(GlamourUiState(page = 2).followFailure)
    }

    @Test fun explicitlyTypedCopyCanClearFailureWithoutChangingLegacyFields() {
        val typed = GlamourUiState(page = 7, error = "tail", followFailure = IllegalStateException())
        val cleared = typed.copy(followFailure = null)
        assertNull(cleared.followFailure)
        assertEquals(7, cleared.page)
        assertEquals("tail", cleared.error)
        assertEquals("tail", cleared.component34())
    }

    @Test fun typedFailureRemainsPartOfEqualityAndStateFlowValue() {
        val before = GlamourUiState()
        val failure = IllegalStateException()
        val failed = before.copy(followFailure = failure)
        assertNotEquals(before, failed)
        val state = MutableStateFlow(before)
        state.value = failed
        assertSame(failed, state.value)
        assertSame(failure, state.value.followFailure)
        state.value = state.value.copy(page = 2)
        assertSame(failure, state.value.followFailure)
    }
}
