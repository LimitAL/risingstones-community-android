package top.cxmeow.risingstones.feature.message.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.message.presentation.MessageViewModel

class MessageAuthorNavigationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun authorAt599() = authorAtWidth(599)
    @Test fun authorAt600() = authorAtWidth(600)
    @Test fun authorAt839() = authorAtWidth(839)
    @Test fun authorAt840() = authorAtWidth(840)

    private fun authorAtWidth(initialWidth: Int) {
        val width = mutableStateOf(initialWidth)
        val service = AttributedMessageFixture()
        val model = MessageViewModel(service)
        val opened = mutableListOf<MessageAuthorTarget>()
        rule.setContent { MaterialTheme {
            RisingStonesMessageAuthorNavigation({ opened += it }) {
                Box(Modifier.width(width.value.dp).height(1000.dp)) { RisingStonesMessageScreen(model, {}) }
            }
        } }
        rule.onNodeWithText("Mentions").performClick()
        waitFor("Member 0")
        rule.onNodeWithTag("message-content-list").performScrollToNode(hasText("Member 25"))
        rule.onNodeWithText("Member 25").performClick()
        assertEquals(listOf(MessageAuthorTarget.Community("author-25")), opened)
        assertNull(model.state.value.selectedKey)
        assertEquals(1, service.reads)
        rule.runOnIdle { width.value = if (initialWidth < 600) 600 else 599 }
        rule.onNodeWithText("Member 25").assertIsDisplayed()
        rule.runOnIdle { width.value = initialWidth }
        rule.onNodeWithText("Member 25").assertIsDisplayed()
        assertTrue(rule.onNodeWithTag("message-content-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertEquals(1, service.reads)
    }

    @Test fun ownSentMessagesUseSelfAndOpeningNeverAcknowledgesAgain() {
        val service = AttributedMessageFixture()
        val model = MessageViewModel(service)
        val opened = mutableListOf<MessageAuthorTarget>()
        rule.setContent { MaterialTheme {
            RisingStonesMessageAuthorNavigation({ opened += it }) { RisingStonesMessageScreen(model, {}) }
        } }
        rule.onNodeWithText("Comments").performClick()
        waitFor("Member 0")
        rule.onNodeWithText("Sent").performClick()
        rule.waitUntil { service.reads == 2 }
        waitFor("Me")
        rule.onAllNodesWithText("Member 0").assertCountEquals(0)
        rule.onAllNodesWithText("Me").onFirst().performClick()
        assertEquals(listOf(MessageAuthorTarget.Self), opened)
        assertEquals(2, service.reads)
        assertNull(model.state.value.selectedKey)
    }

    @Test fun legacyOrUnavailableIdentityKeepsNameWithoutAnAuthorAction() {
        val legacy = object : MessageService by AttributedMessageFixture() {}
        val model = MessageViewModel(legacy)
        rule.setContent { MaterialTheme {
            RisingStonesMessageAuthorNavigation({ error("No identity should be emitted") }) {
                RisingStonesMessageScreen(model, {})
            }
        } }
        rule.onNodeWithText("Mentions").performClick()
        waitFor("Member 0")
        rule.onAllNodesWithTag("message-author-link").assertCountEquals(0)
    }

    private fun waitFor(text: String) {
        rule.waitUntil(5000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

private class AttributedMessageFixture : MessageAuthorService {
    override val canRead = true
    var reads = 0
    override suspend fun fetchUnreadSummary() = MessageUnreadSummary(0, 0, 0, 0, 0, emptyMap())
    override suspend fun readMessages(query: MessageQuery) = readMessagesWithAuthors(query).page
    override suspend fun readMessagesWithAuthors(query: MessageQuery): MessageAuthorPage {
        reads++
        val rows = (0..30).map { CommunityMessage("entry-$it", query.category, "Member $it", "", "Notice $it",
            "Synthetic content", "", emptyList(), null, null, null, null) }
        val authors = rows.mapIndexed { index, row -> row.key to
            if (query.commentChannel == MessageCommentChannel.Sent) MessageAuthorTarget.Self
            else MessageAuthorTarget.Community("author-$index") }.toMap()
        return MessageAuthorPage(MessagePage(rows, query.page, false), authors)
    }
}
