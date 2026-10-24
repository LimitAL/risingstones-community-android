package top.cxmeow.risingstones.feature.profile.domain

/** Raw optional aggregates from this profile response, independent of loaded relation lists. */
data class ProfileReadCounts(val following: Long?, val followers: Long?, val liked: Long?) {
    init { require(listOfNotNull(following, followers, liked).all { it >= 0 }) }
}
/** Additive projection: published CommunityProfile and ProfileGuildSnapshot ABI stays intact. */
data class ProfileReadSnapshot @JvmOverloads constructor(val profile: CommunityProfile, val guild: ProfileGuildReference?, val counts: ProfileReadCounts,
    /** Null means missing/malformed badge facts; empty means no displayed badges, not no owned badges. */
    val badges:List<ProfileBadge>?=null) {
    fun copy(profile:CommunityProfile,guild:ProfileGuildReference?,counts:ProfileReadCounts):ProfileReadSnapshot=ProfileReadSnapshot(profile,guild,counts,badges)
    companion object {
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun `copy$default`(source:ProfileReadSnapshot,profile:CommunityProfile?,guild:ProfileGuildReference?,counts:ProfileReadCounts?,mask:Int,marker:Any?):ProfileReadSnapshot=
            source.copy(if(mask and 1!=0)source.profile else requireNotNull(profile),if(mask and 2!=0)source.guild else guild,if(mask and 4!=0)source.counts else requireNotNull(counts))
    }
}
