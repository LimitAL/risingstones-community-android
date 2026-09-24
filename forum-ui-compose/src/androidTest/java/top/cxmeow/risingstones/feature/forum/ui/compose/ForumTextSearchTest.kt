package top.cxmeow.risingstones.feature.forum.ui.compose

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumBrowsePage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumContentKind
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumPage
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchField
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumSearchQuery
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumService
import top.cxmeow.risingstones.feature.forum.domain.OfficialForumTextSearchService

/** Synthetic UI coverage only; no official endpoint or account is contacted. */
class ForumTextSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ForumTextSearchTestActivity>()

    @After fun clearFixture() {
        compose.runOnIdle { ForumTextSearchFixture.configuration = null }
    }

    @Test fun searchFieldAt599() = searchFieldAt(599)
    @Test fun searchFieldAt600() = searchFieldAt(600)
    @Test fun searchFieldAt839() = searchFieldAt(839)
    @Test fun searchFieldAt840() = searchFieldAt(840)

    @Test fun explicitSearchMapsPostAndGuideTitleAndBodyToServiceParameters() {
        val cases = listOf(
            OfficialForumContentKind.Post to OfficialForumSearchField.Title,
            OfficialForumContentKind.Post to OfficialForumSearchField.Body,
            OfficialForumContentKind.Guide to OfficialForumSearchField.Title,
            OfficialForumContentKind.Guide to OfficialForumSearchField.Body,
        )
        cases.forEachIndexed { index, (kind, field) ->
            val service = RecordingTextSearchService()
            show(service, listOf(599, 600, 839, 840)[index])
            if (kind == OfficialForumContentKind.Guide) {
                compose.onNodeWithText(text(R.string.forum_guides)).performClick()
            }
            val keywords = "fixture ${kind.name.lowercase()} ${field.name.lowercase()}"
            compose.onNodeWithTag(SearchInput).performTextInput(keywords)
            waitForTag("forum-search-field-Title")
            if (field == OfficialForumSearchField.Body) {
                compose.onNodeWithTag("forum-search-field-Body").performClick()
            }
            compose.onNodeWithTag(SearchSubmit).performClick()
            compose.waitUntil(5_000) { service.calls.size == 1 }

            assertEquals(
                SearchCall(
                    OfficialForumSearchQuery(contentKind = kind, keywords = keywords),
                    field,
                    null,
                ),
                service.calls.single(),
            )
        }
    }

    @Test fun legacyServiceSearchesWithItsTitleContractWithoutShowingFieldChoices() {
        val service = RecordingLegacySearchService()
        show(service, 599)
        compose.onNodeWithTag(SearchInput).performTextInput("legacy title")

        compose.onNodeWithTag("forum-search-field-Title").assertDoesNotExist()
        compose.onNodeWithTag("forum-search-field-Body").assertDoesNotExist()
        compose.onNodeWithTag(SearchSubmit).performClick()
        compose.waitUntil(5_000) { service.queries.size == 1 }

        assertEquals(
            OfficialForumSearchQuery(keywords = "legacy title"),
            service.queries.single(),
        )
    }

    @Test fun changingFieldRestartsAtPageOneAndSelectionSurvivesEveryWindowBoundary() {
        val service = RecordingTextSearchService(paged = true)
        val configuration = show(service, 599)
        compose.onNodeWithTag(SearchInput).performTextInput("paged fixture")
        compose.onNodeWithTag(SearchSubmit).performClick()
        compose.waitUntil(5_000) { service.calls.any { it.query.page == 1 } }

        val loadMore = text(R.string.forum_load_more)
        compose.onNodeWithTag("forum-list-content").performScrollToNode(hasText(loadMore))
        compose.onNodeWithText(loadMore).performClick()
        compose.waitUntil(5_000) { service.calls.any { it.query.page == 2 } }
        assertEquals("fixture-page-1", service.calls.last { it.query.page == 2 }.pageTime)

        compose.onNodeWithTag("forum-search-field-Body").performClick()
        compose.waitUntil(5_000) {
            service.calls.lastOrNull()?.let {
                it.field == OfficialForumSearchField.Body && it.query.page == 1 && it.pageTime == null
            } == true
        }

        listOf(599, 600, 839, 840).forEach { width ->
            compose.runOnIdle { configuration.width = width }
            compose.waitUntil(5_000) { abs(configuration.measuredWidthDp - width) < 0.1f }
            compose.onNodeWithTag("forum-search-field-Body")
                .assertIsDisplayed()
                .assertIsSelected()
            assertInput("paged fixture")
        }
    }

    @Test fun submittedQueryAndBodyFieldSurviveRealActivityRecreation() {
        val service = RecordingTextSearchService()
        show(service, 600)
        compose.onNodeWithTag(SearchInput).performTextInput("recreate fixture")
        compose.onNodeWithTag("forum-search-field-Body").performClick()
        compose.onNodeWithTag(SearchSubmit).performClick()
        compose.waitUntil(5_000) { service.calls.size == 1 }

        compose.activityRule.scenario.recreate()
        waitForTag("forum-search-field-Body")

        assertInput("recreate fixture")
        compose.onNodeWithTag("forum-search-field-Body").assertIsSelected()
        assertEquals(1, service.calls.size)
    }

    private fun searchFieldAt(width: Int) {
        show(RecordingTextSearchService(), width)
        waitForText("Topic")
        compose.onNodeWithTag(SearchInput).performTextInput("width $width")
        compose.onNodeWithTag("forum-search-field-Body").performClick()
        compose.onNodeWithTag("forum-search-field-Title").assertIsDisplayed()
        compose.onNodeWithTag("forum-search-field-Body").assertIsDisplayed().assertIsSelected()
        capture(width)
    }

    private fun capture(width: Int) {
        if (InstrumentationRegistry.getArguments().getString("forumSearchScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                .resolve("forum-search-$width.png").outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(service: OfficialForumService, width: Int): ForumTextSearchConfiguration {
        val configuration = ForumTextSearchConfiguration(service, width)
        compose.runOnIdle { ForumTextSearchFixture.configuration = configuration }
        waitForTag(SearchInput)
        compose.runOnIdle { assertEquals(width.toFloat(), configuration.measuredWidthDp, 0.1f) }
        return configuration
    }

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(value: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun assertInput(value: String) {
        compose.onNodeWithTag(SearchInput).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)),
        )
    }

    private companion object {
        const val SearchInput = "forum-search-input"
        const val SearchSubmit = "forum-search-submit"
    }
}

class ForumTextSearchTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForumTextSearchFixture.configuration?.let { configuration ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = Density(constraints.maxWidth.toFloat() / configuration.width, 1f)
                    SideEffect {
                        configuration.measuredWidthDp = with(density) { constraints.maxWidth.toDp().value }
                    }
                    CompositionLocalProvider(LocalDensity provides density) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().testTag("forum-search-window")) {
                                RisingStonesForumScreen(configuration.service, {})
                            }
                        }
                    }
                }
            }
        }
    }
}

private object ForumTextSearchFixture {
    var configuration by mutableStateOf<ForumTextSearchConfiguration?>(null)
}

private class ForumTextSearchConfiguration(val service: OfficialForumService, width: Int) {
    var width by mutableIntStateOf(width)
    var measuredWidthDp = 0f
}

private data class SearchCall(
    val query: OfficialForumSearchQuery,
    val field: OfficialForumSearchField,
    val pageTime: String?,
)

private class RecordingTextSearchService(
    private val paged: Boolean = false,
) : OfficialForumService by FakeOfficialForumService, OfficialForumTextSearchService {
    val calls = CopyOnWriteArrayList<SearchCall>()

    override suspend fun searchTextPage(
        query: OfficialForumSearchQuery,
        field: OfficialForumSearchField,
        pageTime: String?,
    ): OfficialForumBrowsePage {
        calls += SearchCall(query, field, pageTime)
        val base = FakeOfficialForumService.searchPosts(query).items.single()
        val items = if (paged) List(20) { index ->
            base.copy(id = query.page * 1_000 + index, title = "Fixture page ${query.page} result $index")
        } else listOf(base)
        return OfficialForumBrowsePage(
            OfficialForumPage(items, if (paged) 40 else items.size, query.page),
            "fixture-page-${query.page}",
        )
    }
}

private class RecordingLegacySearchService : OfficialForumService by FakeOfficialForumService {
    val queries = CopyOnWriteArrayList<OfficialForumSearchQuery>()

    override suspend fun searchPosts(query: OfficialForumSearchQuery) =
        FakeOfficialForumService.searchPosts(query).also { queries += query }
}
