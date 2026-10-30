package top.cxmeow.risingstones.feature.profile.domain

/** 本次响应的一行；sourceIndex保留重复UUID的来源位置，不猜规范用户行。 */
data class ProfileRecommendationEntry(val person: ProfileListItem.Person, val reason: String?, val sourceIndex: Int)
data class ProfileRecommendationGroup(val reason: String, val entries: List<ProfileRecommendationEntry>)
/** 无来源总数／分页保证；原始行与无效行数量不等于全局推荐人数。 */
data class ProfileRecommendationSnapshot(val entries: List<ProfileRecommendationEntry>, val sourceRows: Int, val invalidRows: Int) {
    val groups: List<ProfileRecommendationGroup> get() = entries.groupByTo(linkedMapOf()) { it.reason.orEmpty() }
        .map { ProfileRecommendationGroup(it.key, it.value) }
}
/** 当前认证身份的独立只读合同，不接收他人UUID，不提供关注或其他写能力。 */
interface ProfileRecommendationService {
    suspend fun fetchRecommendedUsers(): ProfileRecommendationSnapshot
}
