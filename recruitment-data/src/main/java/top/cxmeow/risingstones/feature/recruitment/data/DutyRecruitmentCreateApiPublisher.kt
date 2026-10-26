package top.cxmeow.risingstones.feature.recruitment.data

import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.recruitment.domain.*
import top.cxmeow.risingstones.network.*

/** 仅纯文字副本招募创建。表单与独立 tempsuid 对齐 iOS；严格成功码与有效 ID，失败不刷新凭据或重放 POST。
 * 不提供媒体、分享动态或管理操作。不可确认响应保留未知结果，由宿主处理恢复。 */
class DutyRecruitmentCreateApiPublisher(
    private val client:RisingStonesPublicApiClient,
    private val sessions:RisingStonesSessionProvider,
    private val catalog: DutyRecruitmentFilterCatalog,
    private val json:Json=Json{ignoreUnknownKeys=true},
    private val temporarySessionId:()->String={UUID.randomUUID().toString()},
):DutyRecruitmentPublisher {
    override suspend fun create(draft:DutyRecruitmentCreateDraft):DutyRecruitmentCreateResult {
        require(!draft.form.sharesToDynamics) { "Sharing is outside this publisher" }
        require(draft.form.issues(catalog).isEmpty()) { "Invalid recruitment form" }
        currentCoroutineContext().ensureActive()
        val scope=(sessions as? RisingStonesCapabilityScopeProvider)?.captureCapabilityScope(setOf(RisingStonesCapability.RecruitmentWrite))
            ?: throw DutyRecruitmentPublishRejected(null,"Current posting session unavailable")
        try {
            val context=RisingStonesRequestContext("api/home/recruit/createRecruitFb",RisingStonesAuthenticationRequirement.Required,RisingStonesCapability.RecruitmentWrite)
            val attempt=scope.beginCapabilityAttempt(context) ?: throw DutyRecruitmentPublishRejected(null,"Current posting session unavailable")
            try {
                val headers=mutableMapOf<String,String>()
                attempt.authorizer.authorize(context,RisingStonesHeaderSink{key,value->headers[key]=value})
                currentCoroutineContext().ensureActive()
                if(!scope.isCurrent() || (attempt as? RisingStonesCapabilityAttemptGuard)?.isCurrent()!=true)throw DutyRecruitmentPublishRejected(null,"Posting session changed before sending")
                fun encoded(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("*","%2A").replace("%7E","~")
                fun pair(key:String,value:String)=encoded(key)+"="+encoded(value)
                val queryTemporaryId=temporarySessionId()
                val f=draft.form
                val composition=requireNotNull(f.composition(catalog))
                val fields=mutableListOf(
                    "fb_type" to f.dutyType,"fb_name" to f.resolvedDutyName,"target_area_id" to requireNotNull(f.targetAreaId).toString(),
                    "progress" to f.text(DutyPublishField.Progress),"strategy" to f.text(DutyPublishField.Strategy),"fb_time" to f.text(DutyPublishField.Schedule),
                    "need_job" to f.neededJobIds.joinToString(","),"contact_info" to f.text(DutyPublishField.Contact),
                    "label" to f.labelIds.joinToString(","),"custom_label" to f.customLabels.joinToString(","),
                    "team_detail" to f.text(DutyPublishField.TeamDetail),"recruit_require" to f.text(DutyPublishField.Requirements),
                    "strategy_desc" to f.text(DutyPublishField.StrategyDescription),"team_composition" to composition.wireValue)
                if(composition==DutyTeamComposition.Alliance) {
                    val teams=buildJsonObject { for(team in listOf("A","B","C")) put(team,buildJsonObject {
                        for(slot in DutyTeamComposition.FullParty.slots) put(slot,f.occupiedJobs["$team.$slot"] ?: "0")
                    }) }
                    fields+="team_position" to teams.toString()
                } else for(slot in listOf("T","H","MT","ST","H1","H2","D1","D2","D3","D4"))
                    fields+=slot to (if(slot in composition.slots)f.occupiedJobs[slot] ?: "0" else "0")
                fields+=listOf("due_day" to f.dueDays.toString(),"status" to if(draft.listsImmediately)"1" else "0",
                    "is_share" to "0","scope" to when(f.visibility){DutyPublishVisibility.Everyone->"1";DutyPublishVisibility.MutualFollowers->"2";DutyPublishVisibility.OnlyMe->"3"},"tempsuid" to temporarySessionId())

                val response=client.execute(RisingStonesApiRequest(context.path,RisingStonesHttpMethod.Post,
                    query=listOf(RisingStonesApiQueryItem("tempsuid",queryTemporaryId)),headers=headers,
                    body=fields.joinToString("&"){pair(it.first,it.second)}.toByteArray(Charsets.UTF_8),contentType="application/x-www-form-urlencoded; charset=UTF-8"))
                currentCoroutineContext().ensureActive()
                if(!scope.isCurrent() || (attempt as? RisingStonesCapabilityAttemptGuard)?.isCurrent()!=true)throw kotlinx.coroutines.CancellationException("Posting session changed")
                if(response.statusCode==401 || response.statusCode==403)throw DutyRecruitmentPublishRejected(null,"Posting authorization refused")
                check(response.statusCode in 200..299){"Post creation outcome unconfirmed"}
                val value=runCatching{json.parseToJsonElement(response.body.toString(Charsets.UTF_8)) as? JsonObject}.getOrNull() ?: error("Post creation outcome unconfirmed")
                val code=value["code"] as? JsonPrimitive
                check(code!=null && !code.isString && code.intOrNull!=null){"Post creation outcome unconfirmed"}
                if(code.intOrNull!=10000) {
                    val validation=value["data"] as? JsonObject
                    val field=(validation?.get("field") as? JsonPrimitive)?.contentOrNull?.let(DutyPublishField::fromServer)
                    val message=(validation?.get("msg") as? JsonPrimitive)?.contentOrNull ?: (value["msg"] as? JsonPrimitive)?.contentOrNull ?: "Recruitment refused"
                    throw DutyRecruitmentPublishRejected(field,message)
                }
                val id=(value["data"] as? JsonPrimitive)?.intOrNull?.takeIf{it>0}
                    ?: error("Recruitment creation outcome unconfirmed")
                check(attempt.complete()){ "Post creation outcome unconfirmed" }
                return DutyRecruitmentCreateResult(id)
            } finally { attempt.close() }
        } finally { scope.close() }
    }
}
