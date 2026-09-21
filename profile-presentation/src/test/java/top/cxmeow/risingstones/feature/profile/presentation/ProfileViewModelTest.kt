package top.cxmeow.risingstones.feature.profile.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.profile.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    @Test fun initialLoadNeverReadsOrAcknowledgesFollowers() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        model.ensureLoaded(); advanceUntilIdle()
        assertEquals(1, service.profiles.size)
        assertTrue(service.reads.isEmpty())
        model.selectSection(ProfileSection.Followers); advanceUntilIdle()
        assertEquals(listOf(ProfileSection.Followers), service.reads.map { it.section })
    }

    @Test fun externalRootHasNoUnopenedSelfHistoryAndRepeatedRootDoesNotResetNestedNavigation() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        val root = ProfileOwner.User("root")
        model.openRoot(root); advanceUntilIdle()
        assertFalse(model.state.value.canNavigateBack)
        assertFalse(model.navigateBack())
        model.openRoot(root); advanceUntilIdle()
        assertEquals(listOf(root), service.profiles)
        model.selectSection(ProfileSection.Followers); advanceUntilIdle()
        val original = model.state.value
        model.open(ProfileOwner.User("child")); advanceUntilIdle()
        model.openRoot(root); advanceUntilIdle()
        assertEquals(ProfileOwner.User("child"), model.state.value.owner)
        assertTrue(model.navigateBack()); advanceUntilIdle()
        assertEquals(original, model.state.value)
        assertFalse(model.navigateBack())
        assertEquals(1, service.reads.size)
    }

    @Test fun changingRootClearsHistoryAndRejectsPendingProfileAndSectionResponses() = runTest {
        val service = Service().apply { pendingSelf = CompletableDeferred() }
        val model = ProfileViewModel(service)
        model.openRoot(ProfileOwner.Self)
        model.selectSection(ProfileSection.Followers); runCurrent()
        model.open(ProfileOwner.User("child")); runCurrent()
        model.openRoot(ProfileOwner.User("replacement")); runCurrent()
        service.pendingSelf!!.complete(Unit); advanceUntilIdle()
        assertEquals("replacement", model.state.value.profile?.uuid)
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(ProfileSection.Overview, model.state.value.section)
        assertFalse(model.navigateBack())
        assertEquals(1, service.reads.size)
    }

    @Test fun ownerHistoryRetainsAllSectionPagesAndRestoresFollowersWithoutAnotherRead() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        model.openRoot(ProfileOwner.User("root")); advanceUntilIdle()
        model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle()
        val posts = model.state.value
        model.selectSection(ProfileSection.Followers); advanceUntilIdle()
        val followers = model.state.value
        model.open(ProfileOwner.User("child")); advanceUntilIdle()
        model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        assertTrue(model.navigateBack()); advanceUntilIdle()
        assertEquals(followers, model.state.value)
        model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        assertEquals(posts, model.state.value)
        model.selectSection(ProfileSection.Followers); advanceUntilIdle()
        assertEquals(followers, model.state.value)
        assertEquals(1, service.reads.count { it.section == ProfileSection.Followers })
        assertEquals(4, service.reads.size)
        model.refreshSection(); advanceUntilIdle()
        assertEquals(2, service.reads.count { it.section == ProfileSection.Followers })
    }

    @Test fun returningToCancelledFollowersShowsExplicitRetryWithoutAcknowledgingAgain() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        model.openRoot(ProfileOwner.Self); advanceUntilIdle()
        service.pendingSelf = CompletableDeferred()
        model.selectSection(ProfileSection.Followers); runCurrent()
        model.open(ProfileOwner.User("child")); runCurrent()
        assertTrue(model.navigateBack()); runCurrent()
        assertEquals(ProfileLoadStatus.Idle, model.state.value.listStatus)
        assertEquals(1, service.reads.size)
        service.pendingSelf!!.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(1, service.reads.size)
    }

    @Test fun clearingViewModelStoreClearsHistoryCachedSectionsAndIgnoresLateResults() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        val store = ViewModelStore().apply { put("profile", model) }
        model.openRoot(ProfileOwner.User("root")); advanceUntilIdle()
        model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        model.selectSection(ProfileSection.Followers); advanceUntilIdle()
        model.open(ProfileOwner.Self); advanceUntilIdle()
        service.pendingSelf = CompletableDeferred()
        model.refreshProfile(); model.selectSection(ProfileSection.Posts); runCurrent()
        store.clear()
        service.pendingSelf!!.complete(Unit); advanceUntilIdle()
        assertEquals(ProfileUiState(), model.state.value)
        assertFalse(model.navigateBack())
    }

    @Test fun returningFromPersonRestoresSectionWithoutAcknowledgingFollowersAgain() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        model.selectSection(ProfileSection.Followers); advanceUntilIdle()
        val original = model.state.value
        model.open(ProfileOwner.User("other")); advanceUntilIdle()
        assertTrue(model.state.value.items.isEmpty())
        model.selectSection(ProfileSection.FavoritePosts); advanceUntilIdle()
        assertEquals(ProfileSection.Overview, model.state.value.section)
        assertTrue(model.navigateBack()); advanceUntilIdle()
        assertEquals(original, model.state.value)
        assertEquals(1, service.reads.size)
    }

    @Test fun failedRefreshRetainsContentAndPaginationRetriesTheSamePage() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        model.ensureLoaded(); advanceUntilIdle()
        model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        service.failure = ProfileException.Network
        model.refreshProfile(); model.refreshSection(); advanceUntilIdle()
        assertNotNull(model.state.value.profile)
        assertEquals(1, model.state.value.items.size)
        assertEquals(ProfileLoadStatus.Failed, model.state.value.listStatus)
        service.failure = null
        model.refreshSection(); advanceUntilIdle()
        service.failure = ProfileException.Network
        model.loadMore(); advanceUntilIdle()
        assertTrue(model.state.value.loadMoreFailed)
        assertEquals(1, model.state.value.page)
        service.failure = null
        model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(1, 1, 1, 2, 2), service.reads.map { it.page })
        assertEquals(2, model.state.value.items.size)
    }

    @Test fun lateProfileAndListResponsesCannotOverwriteAnotherOwner() = runTest {
        val service = Service().apply { pendingSelf = CompletableDeferred() }
        val model = ProfileViewModel(service)
        model.ensureLoaded(); model.selectSection(ProfileSection.Posts); runCurrent()
        model.open(ProfileOwner.User("other")); runCurrent()
        service.pendingSelf!!.complete(Unit); advanceUntilIdle()
        assertEquals("other", model.state.value.profile?.uuid)
        assertEquals(ProfileSection.Overview, model.state.value.section)
        assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun authFailureClearsProfileItemsAndNavigationHistory() = runTest {
        val service = Service()
        val model = ProfileViewModel(service)
        model.ensureLoaded(); model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        model.open(ProfileOwner.User("other")); advanceUntilIdle()
        service.failure = ProfileException.AuthenticationRequired
        model.refreshProfile(); advanceUntilIdle()
        assertNull(model.state.value.profile)
        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.navigateBack())
        assertEquals(ProfileLoadStatus.AuthenticationRequired, model.state.value.profileStatus)
    }

    @Test fun capabilityLossClearsAndDuplicatePagesStopPagination() = runTest {
        val service = Service().apply { duplicate = true }
        val model = ProfileViewModel(service)
        model.selectSection(ProfileSection.Posts); advanceUntilIdle()
        model.loadMore(); advanceUntilIdle()
        assertEquals(1, model.state.value.items.size)
        assertFalse(model.state.value.hasMore)
        service.canRead = false
        model.refreshSection(); advanceUntilIdle()
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(ProfileLoadStatus.Unavailable, model.state.value.listStatus)
        assertEquals(2, service.reads.size)
    }

    @Test fun clearingProtectedContentRejectsLateResponsesAndPreservesCancellationSemantics() = runTest {
        val service = Service().apply { pendingSelf = CompletableDeferred() }
        val model = ProfileViewModel(service)
        model.ensureLoaded(); model.selectSection(ProfileSection.Followers); runCurrent()
        model.clearProtectedContent()
        service.pendingSelf!!.complete(Unit); advanceUntilIdle()
        assertEquals(ProfileUiState(), model.state.value)
    }
}

private class Service : ProfileService {
    override var canRead = true
    val profiles = mutableListOf<ProfileOwner>()
    val reads = mutableListOf<ProfileListQuery>()
    var pendingSelf: CompletableDeferred<Unit>? = null
    var failure: ProfileException? = null
    var duplicate = false
    override suspend fun fetchProfile(owner: ProfileOwner): CommunityProfile {
        profiles += owner
        if (owner == ProfileOwner.Self) withContext(NonCancellable) { pendingSelf?.await() }
        failure?.let { throw it }
        val id = (owner as? ProfileOwner.User)?.uuid ?: "self"
        return CommunityProfile(id, "Fixture $id", "Area", "World", null, "Bio", owner == ProfileOwner.Self, 2, 1, 0)
    }
    override suspend fun readSection(query: ProfileListQuery): ProfilePage {
        reads += query
        if (query.owner == ProfileOwner.Self) withContext(NonCancellable) { pendingSelf?.await() }
        failure?.let { throw it }
        val id = if (duplicate) 1 else query.page
        return ProfilePage(listOf(ProfileListItem.Content(ProfileContentTarget(ProfileContentKind.Post, id),
            "Fixture post", "", emptyList(), null)), query.page, true)
    }
}
