package top.cxmeow.risingstones.feature.profile.domain

import java.time.Instant

/** Self-only official snapshot. Null sections mean unavailable; an empty list is a successful empty response. */
data class ProfileCharacterData @JvmOverloads constructor(
    val uuid: String, val name: String, val area: String, val world: String,
    val jobs: List<ProfileCharacterJob>?, val jobsUnpublished: Boolean,
    val events: List<ProfileCharacterEvent>?, val eventsUnpublished: Boolean,
    val fetchedAt: Instant,
    /** Null means the detail section was unavailable; values never borrow career/event counts. */
    val facts: List<ProfileCharacterFact>? = null,
) {
    // Preserve the original constructor/copy JVM entry points for existing local consumers.
    fun copy(uuid: String, name: String, area: String, world: String, jobs: List<ProfileCharacterJob>?, jobsUnpublished: Boolean,
        events: List<ProfileCharacterEvent>?, eventsUnpublished: Boolean, fetchedAt: Instant): ProfileCharacterData =
        ProfileCharacterData(uuid,name,area,world,jobs,jobsUnpublished,events,eventsUnpublished,fetchedAt,facts)
    companion object {
        @JvmStatic fun `copy$default`(source: ProfileCharacterData, uuid: String?, name: String?, area: String?, world: String?,
            jobs: List<ProfileCharacterJob>?, jobsUnpublished: Boolean, events: List<ProfileCharacterEvent>?, eventsUnpublished: Boolean,
            fetchedAt: Instant?, mask: Int, marker: Any?): ProfileCharacterData = source.copy(
                if(mask and 1!=0)source.uuid else requireNotNull(uuid),if(mask and 2!=0)source.name else requireNotNull(name),
                if(mask and 4!=0)source.area else requireNotNull(area),if(mask and 8!=0)source.world else requireNotNull(world),
                if(mask and 16!=0)source.jobs else jobs,if(mask and 32!=0)source.jobsUnpublished else jobsUnpublished,
                if(mask and 64!=0)source.events else events,if(mask and 128!=0)source.eventsUnpublished else eventsUnpublished,
                if(mask and 256!=0)source.fetchedAt else requireNotNull(fetchedAt))
    }
}
data class ProfileCharacterJob(val name: String, val level: Int, val category: String)
data class ProfileCharacterEvent(val type: String?, val detail: String, val occurredAt: Instant)

/** Self-only observed text, including genuine zero; unpublished never grants access to other owners. */
data class ProfileCharacterFact(val kind: ProfileFactKind, val value: String?, val unpublished: Boolean)
