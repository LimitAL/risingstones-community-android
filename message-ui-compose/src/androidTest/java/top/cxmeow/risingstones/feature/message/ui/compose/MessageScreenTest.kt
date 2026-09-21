package top.cxmeow.risingstones.feature.message.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.message.presentation.MessageViewModel

class MessageScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactExplicitCategorySelectionReadsMessages() = atWidth(599)
    @Test fun mediumExplicitCategorySelectionReadsMessages() = atWidth(600)
    @Test fun upperMediumExplicitCategorySelectionReadsMessages() = atWidth(839)
    @Test fun expandedExplicitCategorySelectionReadsMessages() = atWidth(840)

    @Test fun resizingKeepsCategoryAndExpandedMessageWithoutAnotherRead() {
        val width = mutableStateOf(599)
        val service = Service()
        val model = MessageViewModel(service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(width.value.dp).height(1000.dp)) { RisingStonesMessageScreen(model, {}) }
        } }
        waitFor("System")
        rule.onNodeWithText("System").performClick()
        waitFor("Fixture notice")
        rule.onNodeWithText("Fixture notice").performClick()
        rule.runOnIdle { width.value = 840 }
        rule.onNodeWithText("Fixture notice").assertExists()
        assertEquals("notice", model.state.value.selectedKey)
        assertEquals(1, service.reads.size)
    }

    @Test fun sourceTargetIsDeliveredWithoutAdditionalRead() {
        val service = Service()
        val model = MessageViewModel(service)
        var target: MessageTarget? = null
        rule.setContent { MaterialTheme { RisingStonesMessageScreen(model, {}, onOpenTarget = { target = it }) } }
        waitFor("System")
        rule.onNodeWithText("System").performClick()
        waitFor("Fixture notice")
        rule.onNodeWithText("View source").performClick()
        assertEquals(MessageTarget(MessageTargetKind.Post, 42), target)
        assertEquals(1, service.reads.size)
    }

    private fun atWidth(width: Int) {
        val service = Service()
        val model = MessageViewModel(service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(width.dp).height(1000.dp)) { RisingStonesMessageScreen(model, {}) }
        } }
        waitFor("System")
        assertEquals(0, service.reads.size)
        rule.onNodeWithText("System").performClick()
        waitFor("Fixture notice")
        assertEquals(1, service.reads.size)
        if (width < 600) rule.onNodeWithText("Mentions").assertDoesNotExist()
        else rule.onNodeWithText("Mentions").assertExists()
    }
    private fun waitFor(text: String) {
        rule.waitUntil(5000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

private class Service : MessageService {
    override val canRead = true
    val reads = mutableListOf<MessageQuery>()
    override suspend fun fetchUnreadSummary() = MessageUnreadSummary(0, 0, 0, 0, 0, emptyMap())
    override suspend fun readMessages(query: MessageQuery): MessagePage {
        reads += query
        return MessagePage(listOf(CommunityMessage("notice", query.category, "Member", "World", "Fixture notice",
            "<p>Message body</p>", "", emptyList(), null, MessageTarget(MessageTargetKind.Post, 42), null, null)), 1, false)
    }
}
