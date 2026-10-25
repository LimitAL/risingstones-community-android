package top.cxmeow.risingstones.feature.profile.domain

import java.time.Instant

/** Explicit source identity only; no inferred users or private profile fetches. */
data class ProfileMentionCandidate(val uuid:String,val name:String,val areaName:String,val groupName:String,val avatarUrl:String?)
enum class ProfileMentionCompleteness { Complete, Partial, Unknown }
data class ProfileMentionCandidateSnapshot(
    val candidates:List<ProfileMentionCandidate>,val sourceCount:Long?,val rawRows:Int,
    val invalidRows:Int,val duplicateRows:Int,val completeness:ProfileMentionCompleteness,val loadedAt:Instant,
)
/** Search has no source total; continuation uses the raw wire page, before filtering. */
data class ProfileMentionSearchPage(val candidates:List<ProfileMentionCandidate>,val page:Int,val rawRows:Int,
    val invalidRows:Int,val duplicateRows:Int,val hasMore:Boolean)
