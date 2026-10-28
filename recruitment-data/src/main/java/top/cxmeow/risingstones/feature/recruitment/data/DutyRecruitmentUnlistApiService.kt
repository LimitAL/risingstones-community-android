package top.cxmeow.risingstones.feature.recruitment.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.*

/** 仅认证 PUT shelveRecruitFb，下架 JSON 的 id 为数字、status 固定字符串 0，tempsuid 只在 query。
 * 严格数值业务码 10000，可返回空 data；不获取凭据、不刷新、不重试或改写其他招募状态。
 * 网络、取消、认证及不可读响应不能证明失败，宿主必须持久保留未知尝试。
 */
class DutyRecruitmentUnlistApiService(private val client: RisingStonesPublicApiClient,
    private val sessions: RisingStonesSessionProvider,
    private val temporarySessionId: () -> String = { UUID.randomUUID().toString() },
) : DutyRecruitmentUnlister {
    override suspend fun unlist(id: Int) {
        require(id > 0)
        currentCoroutineContext().ensureActive()
        val scope=(sessions as? RisingStonesCapabilityScopeProvider)?.captureCapabilityScope(setOf(RisingStonesCapability.RecruitmentWrite))
            ?: throw DutyUnlistRejected("Current recruitment session unavailable")
        try {
            val context=RisingStonesRequestContext("api/home/recruit/shelveRecruitFb",RisingStonesAuthenticationRequirement.Required,RisingStonesCapability.RecruitmentWrite)
            val attempt=scope.beginCapabilityAttempt(context) ?: throw DutyUnlistRejected("Current recruitment session unavailable")
            try {
                val headers=mutableMapOf<String,String>()
                attempt.authorizer.authorize(context,RisingStonesHeaderSink { k,v -> headers[k]=v })
                currentCoroutineContext().ensureActive()
                if(!scope.isCurrent() || (attempt as? RisingStonesCapabilityAttemptGuard)?.isCurrent()!=true)throw DutyUnlistRejected("Recruitment session changed before sending")
                val response=client.execute(RisingStonesApiRequest(context.path,RisingStonesHttpMethod.Put,
                    query=listOf(RisingStonesApiQueryItem("tempsuid",temporarySessionId())),headers=headers,
                    body=buildJsonObject { put("id",id);put("status","0") }.toString().toByteArray(),contentType="application/json"))
                currentCoroutineContext().ensureActive()
                if(!scope.isCurrent() || (attempt as? RisingStonesCapabilityAttemptGuard)?.isCurrent()!=true)throw CancellationException("Recruitment actor changed")
                if(response.statusCode !in 200..299)throw DutyUnlistUnconfirmed()
                val value=Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)) as? JsonObject ?: throw DutyUnlistUnconfirmed()
                val code=value["code"] as? JsonPrimitive ?: throw DutyUnlistUnconfirmed()
                if(code.isString || code.intOrNull==null)throw DutyUnlistUnconfirmed()
                if(code.intOrNull in setOf(401,403,10001,10003,10004,10005,10403))throw DutyUnlistUnconfirmed()
                if(code.intOrNull!=10000)throw DutyUnlistRejected("Recruitment operation refused")
                if(!attempt.complete())throw DutyUnlistUnconfirmed()
            } finally { attempt.close() }
        } catch(e: CancellationException) { throw e }
        catch(e: DutyUnlistRejected) { throw e }
        catch(_: Exception) { throw DutyUnlistUnconfirmed() }
        finally { scope.close() }
    }
}
