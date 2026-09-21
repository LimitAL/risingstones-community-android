package top.cxmeow.risingstones.feature.recruitment.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.cxmeow.risingstones.feature.recruitment.domain.DutyRecruitmentException
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivity
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayActivityDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberDetail
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberPage
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayMemberQuery
import top.cxmeow.risingstones.feature.recruitment.domain.RolePlayRecruitmentMember
import top.cxmeow.risingstones.network.RisingStonesApiQueryItem
import top.cxmeow.risingstones.network.RisingStonesApiRequest

/** Uses the owning service's existing authentication and response policy. */
internal class RolePlayDirectoryApiReader(
    private val temporarySessionId: String,
    private val perform: suspend (RisingStonesApiRequest) -> JsonObject,
) {
    suspend fun members(query: RolePlayMemberQuery): RolePlayMemberPage {
        require(query.recruitmentId > 0)
        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceAtLeast(1)
        val parameters = mutableListOf(
            q("id", query.recruitmentId), q("page", page), q("limit", limit),
        )
        query.pageTime?.takeIf(String::isNotBlank)?.let { parameters += q("pageTime", it) }
        val data = get("getRecruitRpMemberListByRpId", parameters)
        val rows = data.rows()
        return RolePlayMemberPage(
            items = rows.mapNotNull { (it as? JsonObject)?.member() },
            page = page,
            hasMore = rows.size >= limit,
            pageTime = data.scalar("pageTime"),
        )
    }

    suspend fun member(id: Int): RolePlayMemberDetail {
        require(id > 0)
        val data = get("getMemberDetail", listOf(q("id", id)))
        return RolePlayMemberDetail(
            id = data.positiveId("id") ?: id,
            recruitmentId = data.positiveId("rp_id"),
            name = data.string("member_name") ?: throw DutyRecruitmentException.MissingPayload,
            identity = data.string("member_identity"),
            avatarUrl = RolePlayActivityHtml.httpsUrl(data.string("avatar_pic")),
            description = data.string("detail_mask"),
            detailImageUrl = RolePlayActivityHtml.httpsUrl(data.string("detail_pic")),
        )
    }

    suspend fun activities(recruitmentId: Int): List<RolePlayActivity> {
        require(recruitmentId > 0)
        return get("getRecruitRpActListByRpId", listOf(q("id", recruitmentId))).rows()
            .mapNotNull { (it as? JsonObject)?.activity() }
            .filter { it.status != 0 }
    }

    suspend fun activity(id: Int): RolePlayActivityDetail {
        require(id > 0)
        val data = get("getActDetail", listOf(q("id", id)))
        val summary = data.activity(id) ?: throw DutyRecruitmentException.MissingPayload
        val html = data.string("detail_mask").orEmpty()
        val parsed = RolePlayActivityHtml.parse(html)
        return RolePlayActivityDetail(summary, html, parsed.blocks, parsed.hasUnsupportedContent)
    }

    private suspend fun get(path: String, parameters: List<RisingStonesApiQueryItem>): JsonObject =
        perform(RisingStonesApiRequest(
            "api/home/recruit/$path",
            query = parameters + q("tempsuid", temporarySessionId),
        ))["data"] as? JsonObject ?: throw DutyRecruitmentException.MissingPayload
}

private fun JsonObject.member(): RolePlayRecruitmentMember? {
    return RolePlayRecruitmentMember(
        id = positiveId("id") ?: return null,
        name = string("member_name") ?: return null,
        identity = string("member_identity"),
        avatarUrl = RolePlayActivityHtml.httpsUrl(string("avatar_pic")),
        description = string("detail_mask"),
        detailImageUrls = listOfNotNull(RolePlayActivityHtml.httpsUrl(string("detail_pic"))),
    )
}

private fun JsonObject.activity(fallbackId: Int? = null): RolePlayActivity? {
    return RolePlayActivity(
        id = positiveId("id") ?: fallbackId ?: return null,
        recruitmentId = positiveId("rp_id"),
        name = string("act_name") ?: return null,
        coverUrl = RolePlayActivityHtml.httpsUrl(string("cover_pic")),
        beginTime = scalar("begin_time"),
        endTime = scalar("end_time"),
        status = scalar("status")?.toIntOrNull(),
        activityStatus = scalar("act_status")?.toIntOrNull(),
    )
}

private fun q(name: String, value: Any) = RisingStonesApiQueryItem(name, value.toString())
private fun JsonObject.rows(): JsonArray = this["rows"] as? JsonArray ?: throw DutyRecruitmentException.MissingPayload
private fun JsonObject.scalar(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.positiveId(name: String): Int? = scalar(name)?.toIntOrNull()?.takeIf { it > 0 }
