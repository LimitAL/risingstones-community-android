package top.cxmeow.risingstones.feature.recruitment.domain

/** 单一下架能力；不包含上架、删除、分享或自动重试。身份与来源行资格由宿主核对。 */
interface DutyRecruitmentUnlister {
    /** 下架明确的正整数招募 ID；只有严格成功响应才正常返回，其他结果不得推断本地状态。 */
    suspend fun unlist(id: Int)
}
/** 明确业务拒绝或发送前无有效资格；允许宿主保留事实并提示用户。 */
class DutyUnlistRejected(message: String) : IllegalStateException(message)
/** 已发送后的结果不可确认；宿主须保留未知尝试，禁止自动重放。 */
class DutyUnlistUnconfirmed : IllegalStateException("Recruitment unlist outcome unconfirmed")
