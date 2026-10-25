package top.cxmeow.risingstones.feature.forum.data

import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.forum.domain.*
import top.cxmeow.risingstones.network.*

/** One scoped text-only POST. No credential refresh, redirects or replay is initiated here. */
class OfficialForumPostApiPublisher(
    private val client:RisingStonesPublicApiClient,
    private val sessions:RisingStonesSessionProvider,
    private val json:Json=Json{ignoreUnknownKeys=true},
    private val temporarySessionId:()->String={UUID.randomUUID().toString()},
):OfficialForumPostPublisher {
    override suspend fun createPost(draft:OfficialForumPostCreateDraft):OfficialForumPostCreateResult {
        require(draft.partId>0 && draft.partId !in OfficialForumPostRules.officialOnlyPartIds)
        require(draft.title.isNotBlank() && draft.title.length<=OfficialForumPostRules.maximumTitleLength && draft.contentHtml.isNotBlank())
        currentCoroutineContext().ensureActive()
        val scope=(sessions as? RisingStonesCapabilityScopeProvider)?.captureCapabilityScope(setOf(RisingStonesCapability.ForumWrite))
            ?: throw OfficialForumPostRejectedException("Current posting session unavailable")
        try {
            val context=RisingStonesRequestContext("api/home/posts/create",RisingStonesAuthenticationRequirement.Required,RisingStonesCapability.ForumWrite)
            val attempt=scope.beginCapabilityAttempt(context) ?: throw OfficialForumPostRejectedException("Current posting session unavailable")
            try {
                val headers=mutableMapOf<String,String>()
                attempt.authorizer.authorize(context,RisingStonesHeaderSink{key,value->headers[key]=value})
                currentCoroutineContext().ensureActive()
                if(!scope.isCurrent() || (attempt as? RisingStonesCapabilityAttemptGuard)?.isCurrent()!=true)throw OfficialForumPostRejectedException("Posting session changed before sending")
                fun encoded(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("*","%2A").replace("%7E","~")
                fun pair(key:String,value:String)=encoded(key)+"="+encoded(value)
                val queryTemporaryId=temporarySessionId()
                val fields=listOf("id" to "","updated_at" to "","type" to "1","part_id" to draft.partId.toString(),"title" to draft.title,"is_share" to "0","content" to draft.contentHtml,"scope" to "1","cover_pic" to "","has_vote" to "0","tempsuid" to temporarySessionId())
                val response=client.execute(RisingStonesApiRequest(context.path,RisingStonesHttpMethod.Post,
                    query=listOf(RisingStonesApiQueryItem("tempsuid",queryTemporaryId)),headers=headers,
                    body=fields.joinToString("&"){pair(it.first,it.second)}.toByteArray(Charsets.UTF_8),contentType="application/x-www-form-urlencoded; charset=UTF-8"))
                currentCoroutineContext().ensureActive()
                if(!scope.isCurrent() || (attempt as? RisingStonesCapabilityAttemptGuard)?.isCurrent()!=true)throw kotlinx.coroutines.CancellationException("Posting session changed")
                if(response.statusCode==401 || response.statusCode==403)throw OfficialForumPostRejectedException("Posting authorization refused")
                check(response.statusCode in 200..299){"Post creation outcome unconfirmed"}
                val value=runCatching{json.parseToJsonElement(response.body.toString(Charsets.UTF_8)) as? JsonObject}.getOrNull() ?: error("Post creation outcome unconfirmed")
                val code=value["code"] as? JsonPrimitive
                check(code!=null && !code.isString && code.intOrNull!=null){"Post creation outcome unconfirmed"}
                if(code.intOrNull!=10000)throw OfficialForumPostRejectedException((value["msg"] as? JsonPrimitive)?.contentOrNull ?: "Post refused")
                val id=((value["data"] as? JsonObject)?.get("id") as? JsonPrimitive)?.intOrNull?.takeIf{it>0}
                    ?: error("Post creation outcome unconfirmed")
                check(attempt.complete()){ "Post creation outcome unconfirmed" }
                return OfficialForumPostCreateResult(id)
            } finally { attempt.close() }
        } finally { scope.close() }
    }
}
