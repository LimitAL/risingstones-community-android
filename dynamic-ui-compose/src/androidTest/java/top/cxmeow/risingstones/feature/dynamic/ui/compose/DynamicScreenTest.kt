package top.cxmeow.risingstones.feature.dynamic.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.dynamic.presentation.DynamicViewModel

class DynamicScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactSelectionOpensDetailAndReplies() {
        show(599)
        waitFor("Feed item")
        rule.onNodeWithText("Feed item").performClick()
        waitFor("Detail body")
        rule.onNodeWithText("Feed item").assertDoesNotExist()
        rule.onNodeWithText("View 1 replies").performClick()
        waitFor("Nested reply")
    }

    @Test fun mediumSelectionKeepsList() = checkTwoPanes(600)
    @Test fun upperMediumSelectionKeepsList() = checkTwoPanes(839)
    @Test fun expandedSelectionKeepsList() = checkTwoPanes(840)

    @Test fun sourceLinkPassesTypedReferenceToNativeNavigation() {
        var opened: DynamicReference? = null
        sourceScreen(allowSource = true, onOpen = { opened = it })
        rule.onNodeWithText("View source").performScrollTo().performClick()
        assertEquals(DynamicOrigin.Glamour, opened?.origin)
        assertEquals("42", opened?.id)
    }

    @Test fun unverifiedSourceCapabilityDoesNotExposeNavigation() {
        sourceScreen(allowSource = false, onOpen = { error("unavailable source") })
        rule.onNodeWithText("View source").assertDoesNotExist()
    }

    private fun sourceScreen(allowSource: Boolean, onOpen: (DynamicReference) -> Unit) {
        val service = object : DynamicService by Service {
            override suspend fun fetchDetail(id: Int) = Service.fetchDetail(id).copy(
                reference = DynamicReference(DynamicOrigin.Glamour, "42", "Source title", "", emptyList()),
            )
        }
        val model = DynamicViewModel(service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(599.dp).height(1000.dp)) {
                RisingStonesDynamicScreen(model, {}, onOpenReference = onOpen,
                    canOpenReference = { allowSource })
            }
        } }
        waitFor("Feed item")
        rule.onNodeWithText("Feed item").performClick()
        waitFor("Source title")
    }

    @Test fun resizingKeepsMeaningfulSelection() {
        val width = mutableStateOf(599)
        val model = DynamicViewModel(Service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(width.value.dp).height(1000.dp)) { RisingStonesDynamicScreen(model, {}) }
        } }
        waitFor("Feed item")
        rule.onNodeWithText("Feed item").performClick()
        waitFor("Detail body")
        rule.runOnIdle { width.value = 840 }
        waitFor("Feed item")
        rule.onNodeWithText("Detail body").assertExists()
        assertEquals(1, model.state.value.selectedId)
    }

    private fun checkTwoPanes(width: Int) {
        show(width)
        waitFor("Feed item")
        rule.onNodeWithText("Feed item").performClick()
        waitFor("Detail body")
        rule.onNodeWithText("Feed item").assertExists()
    }
    private fun show(width: Int) {
        val model = DynamicViewModel(Service)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(width.dp).height(1000.dp)) { RisingStonesDynamicScreen(model, {}) }
        } }
    }
    private fun waitFor(text: String) {
        rule.waitUntil(5000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
private val Author = DynamicAuthor("fixture", "Member", "Area", "World", null)
private fun entry(body: String) = DynamicEntry(1, Author, body, emptyList(), null, 1, 0, false, null)
private object Service : DynamicService {
    override val canRead = true
    override suspend fun fetchFeed(query: DynamicListQuery) = DynamicPage(listOf(entry("Feed item")), 1, false)
    override suspend fun fetchDetail(id: Int) = entry("Detail body")
    override suspend fun fetchComments(id: Int, query: DynamicListQuery) = DynamicPage(
        listOf(DynamicComment(1, Author, "Comment body", emptyList(), null, null, 0, 1)), 1, false)
    override suspend fun fetchReplies(rootParentId: Int, query: DynamicListQuery) = DynamicPage(
        listOf(DynamicComment(2, Author, "Nested reply", emptyList(), null, null, 0, 0)), 1, false)
}
