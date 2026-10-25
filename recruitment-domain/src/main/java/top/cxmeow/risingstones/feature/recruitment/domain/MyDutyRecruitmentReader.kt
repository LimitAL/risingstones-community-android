package top.cxmeow.risingstones.feature.recruitment.domain

import java.time.Instant

/** Independent authenticated read capability, without management mutations. */
interface MyDutyRecruitmentReader {
    suspend fun fetchMyDutyRecruitmentPage(page: Int): MyDutyRecruitmentReadPage
}
data class MyDutyRecruitmentReadItem(val summary: DutyRecruitmentSummary, val sourceStatus: Int?)
data class MyDutyRecruitmentReadPage(val items: List<MyDutyRecruitmentReadItem>, val page: Int,
    val sourceCount: Long?, val rawRows: Int, val invalidRows: Int, val duplicateRows: Int,
    val hasMore: Boolean, val loadedAt: Instant)
