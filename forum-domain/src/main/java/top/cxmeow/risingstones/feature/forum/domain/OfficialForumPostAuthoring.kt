package top.cxmeow.risingstones.feature.forum.domain

/** Optional publishing contract. No transport implementation is supplied by this module. */
interface OfficialForumPostPublisher {
    suspend fun createPost(draft: OfficialForumPostCreateDraft): OfficialForumPostCreateResult
}

data class OfficialForumPostCreateDraft(val partId: Int, val title: String, val contentHtml: String)
data class OfficialForumPostCreateResult(val postId: Int)

/** A confirmed refusal is distinct from an unknown result; neither is automatically retried. */
class OfficialForumPostRejectedException(message: String) : Exception(message)

data class OfficialForumPostDocument(val paragraphs: List<String> = emptyList()) {
    val plainText: String get() = paragraphs.joinToString("\n")
    val isBlank: Boolean get() = paragraphs.all { it.isBlank() }
    fun toHtml(): String = paragraphs.mapNotNull { paragraph ->
        val text = paragraph.trimEnd()
        text.takeIf { it.isNotBlank() }?.let { "<p>${it.htmlEscaped()}</p>" }
    }.joinToString("")
    companion object {
        fun fromPlainText(text: String) = OfficialForumPostDocument(text.split(Regex("\r\n|[\r\n\u000B\u000C\u0085\u2028\u2029]")))
    }
}

object OfficialForumPostRules {
    const val maximumTitleLength = 32
    val officialOnlyPartIds: Set<Int> = setOf(74, 75, 78)
    fun postableParts(parts: List<OfficialForumPartFilter>) = parts.filter { it.id > 0 && it.id !in officialOnlyPartIds }.distinctBy { it.id }
    /** JVM String length is UTF-16, matching the official browser maxlength and reference client. */
    fun titleLength(title: String): Int = title.trim().length
    fun canSubmit(partId: Int?, parts: List<OfficialForumPartFilter>, title: String, document: OfficialForumPostDocument): Boolean =
        partId != null && postableParts(parts).any { it.id == partId } && title.trim().isNotEmpty() &&
            titleLength(title) <= maximumTitleLength && !document.isBlank
}

private fun String.htmlEscaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")
