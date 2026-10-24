package top.cxmeow.risingstones.feature.profile.domain

/** Raw optional aggregates from this profile response, independent of loaded relation lists. */
data class ProfileReadCounts(val following: Long?, val followers: Long?, val liked: Long?) {
    init { require(listOfNotNull(following, followers, liked).all { it >= 0 }) }
}
/** Additive projection: published CommunityProfile and ProfileGuildSnapshot ABI stays intact. */
data class ProfileReadSnapshot(val profile: CommunityProfile, val guild: ProfileGuildReference?, val counts: ProfileReadCounts)
