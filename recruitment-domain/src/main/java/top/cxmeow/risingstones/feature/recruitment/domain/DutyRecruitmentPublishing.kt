package top.cxmeow.risingstones.feature.recruitment.domain

/** Offline authoring contract only. No create/listing HTTP transport is installed. */
interface DutyRecruitmentPublisher { suspend fun create(draft: DutyRecruitmentCreateDraft): DutyRecruitmentCreateResult }
data class DutyRecruitmentCreateResult(val recruitmentId: Int)
data class DutyRecruitmentCreateDraft(val form: DutyRecruitmentPublishForm, val listsImmediately: Boolean)
class DutyRecruitmentPublishRejected(val field: DutyPublishField?, message: String) : Exception(message)

enum class DutyTeamComposition(val wireValue: String) {
    FullParty("满编小队"), LightParty("轻锐小队"), Alliance("团队"), Other("其他");
    val slots: List<String> get() {
        val full = listOf("MT", "ST", "H1", "H2", "D1", "D2", "D3", "D4")
        return when (this) { FullParty -> full; LightParty -> listOf("T", "H", "D1", "D2")
            Alliance -> listOf("A", "B", "C").flatMap { group -> full.map { "$group.$it" } }; Other -> emptyList() }
    }
}
enum class DutyPublishVisibility { Everyone, MutualFollowers, OnlyMe }
enum class DutyPublishField(val limit: Int = 0, val required: Boolean = false) {
    DutyType, DutyName(15, true), Area, Composition, Labels, Progress(15, true), Strategy(15, true), Schedule(15, true),
    Occupied, Needed, TeamDetail(170), Requirements(170), StrategyDescription(170), Contact(30, true), DueDays, Sharing;
    companion object {
        fun fromServer(value: String): DutyPublishField? = when (value) {
            "fb_type" -> DutyType; "fb_name" -> DutyName; "target_area_id" -> Area; "team_composition" -> Composition
            "label", "custom_label" -> Labels; "progress" -> Progress; "strategy" -> Strategy; "fb_time" -> Schedule
            "team_position", "T", "H", "MT", "ST", "H1", "H2", "D1", "D2", "D3", "D4" -> Occupied
            "need_job" -> Needed; "team_detail" -> TeamDetail; "recruit_require" -> Requirements; "strategy_desc" -> StrategyDescription
            "contact_info" -> Contact; "due_day" -> DueDays; "is_share", "scope" -> Sharing; else -> null
        }
    }
}
enum class DutyPublishIssue { Required, TooLong, TooMany }
data class DutyTypeOption(val name: String, val composition: DutyTeamComposition?)
fun DutyRecruitmentFilterCatalog.publishTypes(): List<DutyTypeOption> = catalogs.duties.filter { it.dutyType != "其他" }
    .distinctBy { it.dutyType }.map { duty -> DutyTypeOption(duty.dutyType, DutyTeamComposition.entries.firstOrNull { it.wireValue == duty.teamComposition }) } + DutyTypeOption("其他", null)

/** Values are not truncated while typing; text lengths use UTF-16, as the official maxlength does. */
data class DutyRecruitmentPublishForm(
    val dutyType: String = "绝境战", val dutyName: String = "", val customDutyName: String = "", val targetAreaId: Int? = null,
    val customComposition: DutyTeamComposition = DutyTeamComposition.FullParty,
    val occupiedJobs: Map<String, String> = emptyMap(), val neededJobIds: List<String> = emptyList(),
    val labelIds: List<String> = emptyList(), val customLabels: List<String> = emptyList(),
    val texts: Map<DutyPublishField, String> = emptyMap(), val dueDays: Int = 3,
    val sharesToDynamics: Boolean = false, val visibility: DutyPublishVisibility = DutyPublishVisibility.Everyone,
) {
    val isOther get() = dutyType == "其他"
    val resolvedDutyName get() = (if (isOther) customDutyName else dutyName).trim()
    fun text(field: DutyPublishField): String = texts[field].orEmpty()
    val hasContent get() = listOf(dutyName, customDutyName).any { it.isNotBlank() } || texts.values.any { it.isNotBlank() } ||
        occupiedJobs.isNotEmpty() || neededJobIds.isNotEmpty() || labelIds.isNotEmpty() || customLabels.isNotEmpty()
    fun composition(catalog: DutyRecruitmentFilterCatalog): DutyTeamComposition? = if (isOther) customComposition else catalog.publishTypes().firstOrNull { it.name == dutyType }?.composition
    fun selectType(type: String, catalog: DutyRecruitmentFilterCatalog): DutyRecruitmentPublishForm = if (type == dutyType) this else copy(dutyType = type, dutyName = "", customDutyName = "").pruneSlots(catalog)
    fun pruneSlots(catalog: DutyRecruitmentFilterCatalog): DutyRecruitmentPublishForm {
        val slots = composition(catalog)?.slots ?: return this
        return copy(occupiedJobs = occupiedJobs.filterKeys { it in slots })
    }
    fun reconcile(catalog: DutyRecruitmentFilterCatalog): DutyRecruitmentPublishForm = copy(
        dutyName = dutyName.takeIf { isOther || it in catalog.catalogs.dutyNames(dutyType) }.orEmpty(),
        targetAreaId = targetAreaId?.takeUnless { it == 0 },
        occupiedJobs = occupiedJobs.filterValues { it in catalog.catalogs.jobs },
        neededJobIds = neededJobIds.filter { it in catalog.catalogs.jobs }.distinct(),
        labelIds = labelIds.filter { id -> catalog.labels.any { it.id == id } }.distinct(),
    ).pruneSlots(catalog)
    fun toggleNeeded(id: String): DutyRecruitmentPublishForm = copy(neededJobIds = if (id in neededJobIds) neededJobIds - id else if (neededJobIds.size < 10) neededJobIds + id else neededJobIds)
    fun toggleLabel(id: String): DutyRecruitmentPublishForm = copy(labelIds = if (id in labelIds) labelIds - id else if (labelIds.size + customLabels.size < 3) labelIds + id else labelIds)
    fun addCustomLabel(value: String): DutyRecruitmentPublishForm {
        val label = value.trim()
        return if (label.isEmpty() || label.length > 5 || label in customLabels || labelIds.size + customLabels.size >= 3) this else copy(customLabels = customLabels + label)
    }
    fun issues(catalog: DutyRecruitmentFilterCatalog): Map<DutyPublishField, DutyPublishIssue> = buildMap {
        fun check(field: DutyPublishField, value: String) {
            if (field.required && value.isBlank()) put(field, DutyPublishIssue.Required)
            else if (value.length > field.limit) put(field, DutyPublishIssue.TooLong)
        }
        if (catalog.publishTypes().none { it.name == dutyType }) put(DutyPublishField.DutyType, DutyPublishIssue.Required)
        if (isOther) check(DutyPublishField.DutyName, customDutyName)
        else if (dutyName.isBlank() || dutyName !in catalog.catalogs.dutyNames(dutyType)) put(DutyPublishField.DutyName, DutyPublishIssue.Required)
        if (targetAreaId != -1 && catalog.areas.none { it.id.toIntOrNull()?.let { id -> id > 0 && id == targetAreaId } == true }) put(DutyPublishField.Area, DutyPublishIssue.Required)
        if (composition(catalog) == null) put(DutyPublishField.Composition, DutyPublishIssue.Required)
        if (neededJobIds.isEmpty() || neededJobIds.any { it !in catalog.catalogs.jobs }) put(DutyPublishField.Needed, DutyPublishIssue.Required)
        else if (neededJobIds.size > 10) put(DutyPublishField.Needed, DutyPublishIssue.TooMany)
        if (labelIds.isEmpty() || labelIds.any { id -> catalog.labels.none { it.id == id } }) put(DutyPublishField.Labels, DutyPublishIssue.Required)
        else if (labelIds.size + customLabels.size > 3) put(DutyPublishField.Labels, DutyPublishIssue.TooMany)
        else if (customLabels.any { it.length > 5 }) put(DutyPublishField.Labels, DutyPublishIssue.TooLong)
        for (field in listOf(DutyPublishField.Progress, DutyPublishField.Strategy, DutyPublishField.Schedule, DutyPublishField.TeamDetail, DutyPublishField.Requirements, DutyPublishField.StrategyDescription, DutyPublishField.Contact)) check(field, text(field))
        if (dueDays !in listOf(3, 7, 15)) put(DutyPublishField.DueDays, DutyPublishIssue.Required)
    }
    fun createDraft(catalog: DutyRecruitmentFilterCatalog, listed: Boolean): DutyRecruitmentCreateDraft? {
        if (issues(catalog).isNotEmpty()) return null
        return DutyRecruitmentCreateDraft(copy(dutyType = dutyType.trim(), dutyName = if (isOther) "" else resolvedDutyName,
            customDutyName = if (isOther) resolvedDutyName else "", occupiedJobs = occupiedJobs.filter { it.key in composition(catalog)!!.slots && it.value in catalog.catalogs.jobs },
            texts = texts.mapValues { it.value.trim() }, customLabels = customLabels.map { it.trim() }.filter { it.isNotEmpty() }), listed)
    }
}
