package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.personaldata.domain.ExplorationBoard
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataExplorationService
import top.cxmeow.risingstones.feature.personaldata.presentation.ExplorationViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.ExplorationViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.ExplorationError

/** Retains only explicitly opened exploration pages and owns their complete lifetime. */
internal class PersonalDataExplorationModelOwner : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val models = mutableMapOf<ExplorationBoard, ExplorationViewModel>()
    private var currentService: PersonalDataExplorationService? = null
    private val observers = mutableListOf<Job>()
    private val rejected = MutableStateFlow(false)
    val authenticationRejected = rejected.asStateFlow()

    fun model(service: PersonalDataExplorationService, board: ExplorationBoard): ExplorationViewModel {
        if (currentService !== service) {
            clearProtectedContent()
            currentService = service
        }
        return models.getOrPut(board) {
            ViewModelProvider(this, ExplorationViewModelFactory(service, board))[board.name, ExplorationViewModel::class.java].also { model ->
                // Requests can finish after navigation away; every retained child still revokes siblings.
                observers += viewModelScope.launch {
                    model.state.collect { state ->
                        if (state.error == ExplorationError.AuthenticationRequired) rejected.value = true
                    }
                }
            }
        }
    }

    fun clearProtectedContent() {
        observers.forEach(Job::cancel)
        observers.clear()
        models.values.forEach(ExplorationViewModel::clearProtectedContent)
        models.clear()
        viewModelStore.clear()
        currentService = null
        rejected.value = false
    }

    override fun onCleared() {
        clearProtectedContent()
        super.onCleared()
    }
}
