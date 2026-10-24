package top.cxmeow.risingstones.feature.profile.domain

import java.time.Instant

/** Self-only official snapshot. Null sections mean unavailable; an empty list is a successful empty response. */
data class ProfileCharacterData(
    val uuid: String, val name: String, val area: String, val world: String,
    val jobs: List<ProfileCharacterJob>?, val jobsUnpublished: Boolean,
    val events: List<ProfileCharacterEvent>?, val eventsUnpublished: Boolean,
    val fetchedAt: Instant,
)
data class ProfileCharacterJob(val name: String, val level: Int, val category: String)
data class ProfileCharacterEvent(val type: String?, val detail: String, val occurredAt: Instant)
