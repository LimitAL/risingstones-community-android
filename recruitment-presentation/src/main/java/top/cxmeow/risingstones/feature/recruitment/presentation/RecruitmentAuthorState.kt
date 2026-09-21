package top.cxmeow.risingstones.feature.recruitment.presentation

data class RecruitmentAuthorState(
    val communityAuthors: Map<Int, String> = emptyMap(),
    val selectedAuthorUuid: String? = null,
    val reviewAuthors: Map<String, String> = emptyMap(),
    val subcommentAuthors: Map<String, Map<String, String>> = emptyMap(),
)
