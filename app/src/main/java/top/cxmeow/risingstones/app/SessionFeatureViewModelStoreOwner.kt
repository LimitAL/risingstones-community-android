package top.cxmeow.risingstones.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.cxmeow.risingstones.core.auth.RisingStonesCapability
import top.cxmeow.risingstones.core.auth.RisingStonesCredentialSource
import top.cxmeow.risingstones.core.auth.RisingStonesSessionState

/** Retained with the runtime across configuration changes, cleared when credentials change. */
internal class SessionFeatureViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var activeCapabilities: Set<RisingStonesCapability>? = null
    private var activeSource: RisingStonesCredentialSource? = null

    private var credentialRevision: Long? = null

    fun synchronize(state: RisingStonesSessionState, revision: Long? = null) {
        val credentialChanged = revision != null && credentialRevision != null && revision != credentialRevision
        if (credentialChanged) clear()
        if (revision != null) credentialRevision = revision
        if (state is RisingStonesSessionState.Active) {
            val previous = activeCapabilities
            if (!credentialChanged && previous != null && (previous.any { it !in state.capabilities } || activeSource != state.source)) {
                clear()
            }
            activeCapabilities = state.capabilities.toSet()
            activeSource = state.source
        } else {
            if (!credentialChanged) clear()
            activeCapabilities = null
            activeSource = null
        }
    }

    private fun clear() {
        viewModelStore.clear()
        mutableRevision.value += 1
    }
}
