package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cxmeow.risingstones.feature.personaldata.domain.*

enum class PersonalDataReadingLoadStatus { Idle, Loading, Loaded, Failed, AuthRequired, Unavailable }
enum class PersonalDataReadingFailure { AuthenticationRequired, Unavailable, LoadFailed }
enum class PersonalDataSetFilter { All, Recorded, Unrecorded }
enum class PersonalDataSetSort { OldestFirst, NewestFirst }
enum class PersonalDataSetCompletion { Unrecorded, Partial, Complete, Unknown }

data class PersonalDataReadingCriteria(
    val query: String = "",
    val category: String? = null,
    val setFilter: PersonalDataSetFilter = PersonalDataSetFilter.All,
    val setSort: PersonalDataSetSort = PersonalDataSetSort.OldestFirst,
    val visibleLimit: Int = 10,
)

data class PersonalDataReadingPageState(
    val status: PersonalDataReadingLoadStatus = PersonalDataReadingLoadStatus.Idle,
    val failure: PersonalDataReadingFailure? = null,
    val hasLoaded: Boolean = false,
    val fishingRows: List<PersonalDataFishingRank> = emptyList(),
    val races: List<PersonalDataRaceUsage> = emptyList(),
    val setRecords: List<PersonalDataGlamourSetRecord> = emptyList(),
    val criteria: PersonalDataReadingCriteria = PersonalDataReadingCriteria(),
)

data class PersonalDataSetRow(
    val key: String,
    val catalog: GlamourCatalogSet?,
    val record: PersonalDataGlamourSetRecord?,
    val knownItemIds: Set<Int>,
    val completion: PersonalDataSetCompletion,
) {
    val setId: Int get() = record?.setId ?: requireNotNull(catalog).mirageSetId
}

data class PersonalDataSetsProgress(val completed: Int, val total: Int)

data class PersonalDataReadingUiState(
    val activePage: PersonalDataReadingPage? = null,
    val pages: Map<PersonalDataReadingPage, PersonalDataReadingPageState> = emptyMap(),
    val catalogs: PersonalDataOfficialCatalogs? = null,
    val catalogStatus: PersonalDataReadingLoadStatus = PersonalDataReadingLoadStatus.Idle,
    val catalogFailure: PersonalDataReadingFailure? = null,
    val selectedSetKey: String? = null,
) {
    val currentPageState: PersonalDataReadingPageState get() = pages[activePage] ?: PersonalDataReadingPageState()
    val query: String get() = currentPageState.criteria.query
    val category: String? get() = currentPageState.criteria.category
    val setFilter: PersonalDataSetFilter get() = currentPageState.criteria.setFilter
    val setSort: PersonalDataSetSort get() = currentPageState.criteria.setSort
    val visibleLimit: Int get() = currentPageState.criteria.visibleLimit
    val fishingCategories: List<String> get() = currentPageState.fishingRows.map { it.category.orEmpty() }.distinct()
    val visibleFishingRows: List<PersonalDataFishingRank> get() = filteredFishingRows.take(visibleLimit)
    val visibleRaces: List<PersonalDataRaceUsage> get() = filteredRaces.take(visibleLimit)
    val visibleSets: List<PersonalDataSetRow> get() = filteredSets.take(visibleLimit)
    val totalFiltered: Int get() = when (activePage) {
        PersonalDataReadingPage.Fish, PersonalDataReadingPage.Baits -> filteredFishingRows.size
        PersonalDataReadingPage.Races -> filteredRaces.size
        PersonalDataReadingPage.Sets -> filteredSets.size
        null -> 0
    }
    val hasMore: Boolean get() = totalFiltered > visibleLimit
    val selectedSet: PersonalDataSetRow? get() = allSets.firstOrNull { it.key == selectedSetKey }
    val setsProgress: PersonalDataSetsProgress? get() {
        if (pages[PersonalDataReadingPage.Sets]?.hasLoaded != true) return null
        val valid = canonicalSets.filter(::validCatalogSet).map { it.mirageSetId }.toSet()
        if (valid.isEmpty()) return null
        val complete = allSets.filter { it.completion == PersonalDataSetCompletion.Complete }.map { it.setId }.toSet()
        return PersonalDataSetsProgress(complete.intersect(valid).size, valid.size)
    }

    private val filteredFishingRows: List<PersonalDataFishingRank> get() = currentPageState.fishingRows.filter {
        it.name.contains(query.trim(), ignoreCase = true) && (category == null || it.category.orEmpty() == category)
    }.sortedByDescending { it.count }
    private val filteredRaces: List<PersonalDataRaceUsage> get() = currentPageState.races.filter {
        "${it.race} ${it.gender}".contains(query.trim(), ignoreCase = true)
    }.sortedWith(compareByDescending<PersonalDataRaceUsage> { it.proportion?.takeIf(Double::isFinite) != null }
        .thenByDescending { it.proportion?.takeIf(Double::isFinite) })
    private val canonicalSets: List<GlamourCatalogSet> get() = catalogs?.glamour?.sets.orEmpty()
        .filter { it.mirageSetId > 0 }.groupBy { it.mirageSetId }.values.map { duplicates ->
            duplicates.firstOrNull(::validCatalogSet) ?: duplicates.first()
        }
    val allSets: List<PersonalDataSetRow> get() {
        val page = pages[PersonalDataReadingPage.Sets] ?: return emptyList()
        val catalogMap = canonicalSets.associateBy { it.mirageSetId }
        val counts = mutableMapOf<String, Int>()
        val records = page.setRecords.map { record ->
            val occurrence = counts.getOrDefault(record.key, 0)
            counts[record.key] = occurrence + 1
            val catalog = catalogMap[record.setId]
            val expected = catalog?.items.orEmpty().map { it.itemId }.filter { it > 0 }.toSet()
            val completion = when {
                catalog == null || !validCatalogSet(catalog) || record.hasInvalidItemIds ||
                    record.itemIds.any { it <= 0 || it !in expected } -> PersonalDataSetCompletion.Unknown
                record.itemIds == expected -> PersonalDataSetCompletion.Complete
                else -> PersonalDataSetCompletion.Partial
            }
            PersonalDataSetRow("record:${record.key.length}:${record.key}:$occurrence", catalog, record,
                record.itemIds.intersect(expected), completion)
        }
        if (!page.hasLoaded || canonicalSets.none(::validCatalogSet)) return records
        val recordedIds = page.setRecords.map { it.setId }.toSet()
        return records + canonicalSets.filter { it.mirageSetId !in recordedIds }.map {
            PersonalDataSetRow("catalog:${it.mirageSetId}", it, null, emptySet(),
                if (validCatalogSet(it)) PersonalDataSetCompletion.Unrecorded else PersonalDataSetCompletion.Unknown)
        }
    }
    internal val filteredSets: List<PersonalDataSetRow> get() {
        val criteria = pages[PersonalDataReadingPage.Sets]?.criteria ?: PersonalDataReadingCriteria()
        val filtered = allSets.filter { row ->
            (row.catalog?.name.orEmpty().contains(criteria.query.trim(), ignoreCase = true) ||
                row.setId.toString().contains(criteria.query.trim())) && when (criteria.setFilter) {
                PersonalDataSetFilter.All -> true
                PersonalDataSetFilter.Recorded -> row.record != null
                PersonalDataSetFilter.Unrecorded -> row.record == null && row.completion == PersonalDataSetCompletion.Unrecorded
            }
        }
        return filtered.sortedWith { a, b ->
            when {
                a.record == null && b.record != null -> 1
                a.record != null && b.record == null -> -1
                a.record == null && b.record == null -> compareValues(a.setId, b.setId).let {
                    if (criteria.setSort == PersonalDataSetSort.NewestFirst) -it else it
                }
                a.record?.recordedAt == null && b.record?.recordedAt != null -> 1
                a.record?.recordedAt != null && b.record?.recordedAt == null -> -1
                else -> {
                    val dates = compareValues(a.record?.recordedAt, b.record?.recordedAt)
                    val ordered = if (criteria.setSort == PersonalDataSetSort.NewestFirst) -dates else dates
                    if (ordered != 0) ordered else compareValues(a.setId, b.setId).takeIf { it != 0 } ?: a.key.compareTo(b.key)
                }
            }
        }
    }
}

private fun validCatalogSet(set: GlamourCatalogSet): Boolean = set.mirageSetId > 0 &&
    set.items.isNotEmpty() && set.items.all { it.itemId > 0 }

class PersonalDataReadingViewModel(private val service: PersonalDataService) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonalDataReadingUiState())
    val state: StateFlow<PersonalDataReadingUiState> = mutableState.asStateFlow()
    val supportsReading: Boolean get() = service is PersonalDataReadingService
    val hasCommunityIdentity: Boolean get() = !isCleared && service.hasCommunityIdentity
    private var pageJob: Job? = null
    private var catalogJob: Job? = null
    private var pageGeneration = 0L
    private var catalogGeneration = 0L
    private var isCleared = false

    fun open(page: PersonalDataReadingPage) {
        if (!requireIdentity(page)) return
        if (state.value.activePage != page) {
            cancelRequests()
            mutableState.update { it.copy(activePage = page) }
        }
        if (!supportsReading) {
            updatePage(page) { it.copy(status = PersonalDataReadingLoadStatus.Unavailable, failure = PersonalDataReadingFailure.Unavailable) }
            return
        }
        val current = state.value.currentPageState
        if (!current.hasLoaded && current.status != PersonalDataReadingLoadStatus.Loading) loadPage(page)
        if (page == PersonalDataReadingPage.Sets) loadCatalogs(force = false)
    }

    fun close() {
        if (isCleared) return
        if (!service.hasCommunityIdentity) { clearProtectedContent(); return }
        cancelRequests()
        mutableState.update { it.copy(activePage = null) }
    }

    fun refresh() {
        if (!requireIdentity()) return
        state.value.activePage?.let { if (supportsReading) loadPage(it) }
    }

    fun retryCatalogs() {
        if (!requireIdentity() || !supportsReading || state.value.activePage != PersonalDataReadingPage.Sets) return
        if (state.value.catalogStatus != PersonalDataReadingLoadStatus.Loading) loadCatalogs(force = true)
    }

    fun updateQuery(query: String) = updateCriteria { it.copy(query = query, visibleLimit = 10) }
    fun selectCategory(category: String?) {
        if (!requireIdentity()) return
        if (state.value.activePage in listOf(PersonalDataReadingPage.Fish, PersonalDataReadingPage.Baits)) {
            updateCriteria { it.copy(category = category, visibleLimit = 10) }
        }
    }
    fun setSetFilter(filter: PersonalDataSetFilter) {
        if (!requireIdentity()) return
        if (state.value.activePage == PersonalDataReadingPage.Sets) updateCriteria { it.copy(setFilter = filter, visibleLimit = 10) }
    }
    fun setSetSort(sort: PersonalDataSetSort) {
        if (!requireIdentity()) return
        if (state.value.activePage == PersonalDataReadingPage.Sets) updateCriteria { it.copy(setSort = sort, visibleLimit = 10) }
    }
    fun showMore() {
        if (!requireIdentity()) return
        if (state.value.hasMore) updateCriteria { it.copy(visibleLimit = (it.visibleLimit.toLong() + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    }
    fun selectSet(key: String) {
        if (!requireIdentity() || state.value.activePage != PersonalDataReadingPage.Sets) return
        if (state.value.filteredSets.any { it.key == key }) mutableState.update { it.copy(selectedSetKey = key) }
    }
    fun clearSetSelection() {
        if (requireIdentity()) mutableState.update { it.copy(selectedSetKey = null) }
    }

    private fun updateCriteria(change: (PersonalDataReadingCriteria) -> PersonalDataReadingCriteria) {
        if (!requireIdentity()) return
        val page = state.value.activePage ?: return
        updatePage(page) { it.copy(criteria = change(it.criteria)) }
        reconcileSelection()
    }

    private fun loadPage(page: PersonalDataReadingPage) {
        val reader = service as? PersonalDataReadingService ?: return
        val generation = ++pageGeneration
        pageJob?.cancel()
        updatePage(page) { it.copy(status = PersonalDataReadingLoadStatus.Loading, failure = null) }
        pageJob = viewModelScope.launch {
            try {
                val result = when (page) {
                    PersonalDataReadingPage.Fish -> PersonalDataReadingPageState(fishingRows = reader.fetchFishingRanking(PersonalDataFishingRankingKind.Fish))
                    PersonalDataReadingPage.Baits -> PersonalDataReadingPageState(fishingRows = reader.fetchFishingRanking(PersonalDataFishingRankingKind.Bait))
                    PersonalDataReadingPage.Races -> PersonalDataReadingPageState(races = reader.fetchRaceUsage())
                    PersonalDataReadingPage.Sets -> PersonalDataReadingPageState(setRecords = reader.fetchGlamourSetRecords())
                }
                currentCoroutineContext().ensureActive()
                if (generation == pageGeneration && requireIdentity()) {
                    updatePage(page) { result.copy(status = PersonalDataReadingLoadStatus.Loaded, hasLoaded = true, criteria = it.criteria) }
                    reconcileSelection()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == pageGeneration && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else updatePage(page) { it.copy(status = PersonalDataReadingLoadStatus.Failed, failure = PersonalDataReadingFailure.LoadFailed) }
                }
            } finally {
                if (generation == pageGeneration && requireIdentity()) {
                    pageJob = null
                    updatePage(page) { it.finishLoading() }
                }
            }
        }
    }

    private fun loadCatalogs(force: Boolean) {
        if (!force && state.value.catalogStatus in listOf(PersonalDataReadingLoadStatus.Loaded, PersonalDataReadingLoadStatus.Loading)) return
        val generation = ++catalogGeneration
        catalogJob?.cancel()
        mutableState.update { it.copy(catalogStatus = PersonalDataReadingLoadStatus.Loading, catalogFailure = null) }
        catalogJob = viewModelScope.launch {
            try {
                val catalogs = service.fetchOfficialCatalogs()
                currentCoroutineContext().ensureActive()
                if (generation == catalogGeneration && requireIdentity()) {
                    if (catalogs.glamour?.sets.orEmpty().none(::validCatalogSet)) {
                        mutableState.update { it.copy(catalogStatus = PersonalDataReadingLoadStatus.Unavailable, catalogFailure = PersonalDataReadingFailure.Unavailable) }
                    } else {
                        mutableState.update { it.copy(catalogs = catalogs, catalogStatus = PersonalDataReadingLoadStatus.Loaded, catalogFailure = null) }
                        reconcileSelection()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == catalogGeneration && requireIdentity()) {
                    if (error is PersonalDataException.AuthenticationRequired) authenticationFailed()
                    else mutableState.update { it.copy(catalogStatus = PersonalDataReadingLoadStatus.Failed, catalogFailure = PersonalDataReadingFailure.LoadFailed) }
                }
            } finally {
                if (generation == catalogGeneration && requireIdentity()) {
                    catalogJob = null
                    mutableState.update { it.finishCatalogLoading() }
                }
            }
        }
    }

    fun clearProtectedContent() {
        cancelRequests()
        mutableState.value = PersonalDataReadingUiState()
    }

    override fun onCleared() {
        isCleared = true
        clearProtectedContent()
        super.onCleared()
    }

    private fun cancelRequests() {
        ++pageGeneration; ++catalogGeneration
        pageJob?.cancel(); catalogJob?.cancel()
        pageJob = null; catalogJob = null
        mutableState.update { old -> old.copy(pages = old.pages.mapValues { it.value.finishLoading() }).finishCatalogLoading() }
    }
    private fun updatePage(page: PersonalDataReadingPage, transform: (PersonalDataReadingPageState) -> PersonalDataReadingPageState) {
        mutableState.update { it.copy(pages = it.pages + (page to transform(it.pages[page] ?: PersonalDataReadingPageState()))) }
    }
    private fun reconcileSelection() {
        if (state.value.selectedSetKey != null && state.value.filteredSets.none { it.key == state.value.selectedSetKey }) {
            mutableState.update { it.copy(selectedSetKey = null) }
        }
    }
    private fun requireIdentity(page: PersonalDataReadingPage? = state.value.activePage): Boolean {
        if (isCleared) return false
        if (service.hasCommunityIdentity) return true
        authenticationFailed(page)
        return false
    }
    private fun authenticationFailed(page: PersonalDataReadingPage? = state.value.activePage) {
        clearProtectedContent()
        mutableState.value = PersonalDataReadingUiState(activePage = page,
            pages = page?.let { mapOf(it to PersonalDataReadingPageState(status = PersonalDataReadingLoadStatus.AuthRequired,
                failure = PersonalDataReadingFailure.AuthenticationRequired)) }.orEmpty(),
            catalogStatus = PersonalDataReadingLoadStatus.AuthRequired, catalogFailure = PersonalDataReadingFailure.AuthenticationRequired)
    }
}

private fun PersonalDataReadingPageState.finishLoading(): PersonalDataReadingPageState =
    if (status == PersonalDataReadingLoadStatus.Loading) copy(status = if (hasLoaded) PersonalDataReadingLoadStatus.Loaded else PersonalDataReadingLoadStatus.Idle) else this
private fun PersonalDataReadingUiState.finishCatalogLoading(): PersonalDataReadingUiState =
    if (catalogStatus == PersonalDataReadingLoadStatus.Loading) copy(catalogStatus = if (catalogs == null) PersonalDataReadingLoadStatus.Idle else PersonalDataReadingLoadStatus.Loaded) else this

class PersonalDataReadingViewModelFactory(private val service: PersonalDataService) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PersonalDataReadingViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return PersonalDataReadingViewModel(service) as T
    }
}
