package top.cxmeow.risingstones.feature.personaldata.ui.compose

import androidx.lifecycle.ViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataShareViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import top.cxmeow.risingstones.feature.personaldata.domain.PersonalDataService
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataReadingViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataReadingViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataDashboardViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataDashboardViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataFrontlineViewModelFactory
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUltimateViewModel
import top.cxmeow.risingstones.feature.personaldata.presentation.PersonalDataUltimateViewModelFactory

/** One stable model bundle per service reference; replacement disposes the entire previous scope. */
internal class PersonalDataScreenModels(
    val main: PersonalDataViewModel,
    val reading: PersonalDataReadingViewModel,
    val exploration: PersonalDataExplorationModelOwner,
    val dashboard: PersonalDataDashboardViewModel,
    val frontline: PersonalDataFrontlineViewModel,
    val ultimate: PersonalDataUltimateViewModel,
    val share: PersonalDataShareViewModel,
)

internal class PersonalDataScreenModelOwner : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    var currentModels: PersonalDataScreenModels? = null
        private set
    private var currentService: PersonalDataService? = null
    private var isCleared = false

    fun models(service: PersonalDataService): PersonalDataScreenModels {
        check(!isCleared) { "Personal data model owner has been cleared" }
        currentModels?.takeIf { currentService === service }?.let { return it }
        clearModels()
        currentService = service
        return PersonalDataScreenModels(
            share = ViewModelProvider(this, ViewModelProvider.NewInstanceFactory())["share", PersonalDataShareViewModel::class.java],
            main = ViewModelProvider(this, PersonalDataViewModelFactory(service))["main", PersonalDataViewModel::class.java],
            reading = ViewModelProvider(this, PersonalDataReadingViewModelFactory(service))["reading", PersonalDataReadingViewModel::class.java],
            exploration = ViewModelProvider(this, ViewModelProvider.NewInstanceFactory())["exploration", PersonalDataExplorationModelOwner::class.java],
            dashboard = ViewModelProvider(this, PersonalDataDashboardViewModelFactory(service))["dashboard", PersonalDataDashboardViewModel::class.java],
            frontline = ViewModelProvider(this, PersonalDataFrontlineViewModelFactory(service))["frontline", PersonalDataFrontlineViewModel::class.java],
            ultimate = ViewModelProvider(this, PersonalDataUltimateViewModelFactory(service))["ultimate", PersonalDataUltimateViewModel::class.java],
        ).also { currentModels = it }
    }

    private fun clearModels() {
        currentModels?.let {
            // Clear observable values synchronously before cancelling and disposing every child.
            it.main.clearProtectedContent()
            it.reading.clearProtectedContent()
            it.exploration.clearProtectedContent()
            it.dashboard.clearProtectedContent()
            it.frontline.clearProtectedContent()
            it.ultimate.clearProtectedContent()
            it.share.clearProtectedContent()
        }
        currentModels = null
        currentService = null
        viewModelStore.clear()
    }

    override fun onCleared() {
        isCleared = true
        clearModels()
        super.onCleared()
    }
}
