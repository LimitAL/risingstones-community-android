package top.cxmeow.risingstones.feature.recruitment.domain

/** Optional owner-bound management capability. No HTTP transport or default service wiring is provided. */
interface DutyRecruitmentManager {
    suspend fun fetchMine(page: Int, limit: Int): MyDutyRecruitmentPage
    suspend fun unlist(id: Int)
    suspend fun relist(id: Int, options: DutyRecruitmentRelistOptions)
    suspend fun delete(id: Int)
}
/** End-of-page is a source fact; consumers must not infer it from filtered or deduplicated rows. */
data class MyDutyRecruitmentPage(val items: List<DutyRecruitmentSummary>, val currentPage: Int, val reachedEnd: Boolean, val totalCount: Int? = null)
data class DutyRecruitmentRelistOptions(val dueDays: Int = 3, val sharesToDynamics: Boolean = false,
    val visibility: DutyPublishVisibility = DutyPublishVisibility.Everyone) {
    val valid: Boolean get() = dueDays in listOf(3, 7, 15)
}
