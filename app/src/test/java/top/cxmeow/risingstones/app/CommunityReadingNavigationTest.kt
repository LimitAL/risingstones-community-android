package top.cxmeow.risingstones.app

import androidx.lifecycle.ViewModel
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

class CommunityReadingNavigationTest {
    @Test fun authorContentAuthorReturnsThroughActualLayersAndReleasesOnlyPoppedModels() {
        val model = navigation()
        model.openAuthor("author-a")
        val source = model.entries.value.single()
        val sourceModel = ReadingTestModel().also { source.viewModelStore.put("profile", it) }
        model.open(CommunityDestination.Post(42))
        model.openAuthor("author-b")
        val nested = ReadingTestModel().also { model.entries.value.last().viewModelStore.put("profile", it) }
        assertEquals(3, model.entries.value.size)
        model.back()
        assertTrue(nested.cleared)
        assertFalse(sourceModel.cleared)
        assertEquals(CommunityDestination.Post(42), model.entries.value.last().destination)
        model.back()
        assertSame(source, model.entries.value.single())
        model.back()
        assertTrue(sourceModel.cleared)
        assertTrue(model.entries.value.isEmpty())
    }

    @Test fun sameTopDestinationIsIdempotentButSameAuthorInAnotherLayerIsAllowed() {
        val model = navigation()
        repeat(3) { model.openAuthor("author-a") }
        assertEquals(1, model.entries.value.size)
        model.open(CommunityDestination.Post(42))
        model.openAuthor("author-a")
        assertEquals(3, model.entries.value.size)
        assertEquals(3, model.entries.value.map { it.key }.distinct().size)
    }

    @Test fun missingIdentityOrUnverifiedCapabilitiesDoNotCreateDestinations() {
        val model = CommunityReadingNavigation()
        model.synchronize(CommunityReadingAccess(), 0)
        model.openAuthor("author")
        model.openAuthor(" ")
        model.open(CommunityDestination.Dynamic(2))
        model.open(CommunityDestination.Glamour(3))
        model.open(CommunityDestination.Recruitment(4, RecruitmentBoardKind.Guild))
        model.open(CommunityDestination.Guild)
        model.open(CommunityDestination.GuildPhoto(42))
        model.open(CommunityDestination.Post(0))
        assertTrue(model.entries.value.isEmpty())
        model.open(CommunityDestination.Post(42))
        model.open(CommunityDestination.Recruitment(4, RecruitmentBoardKind.RolePlay))
        assertEquals(2, model.entries.value.size)
    }

    @Test fun revalidationPreservesButRevocationOrCredentialRevisionClearsAllLayers() {
        val model = navigation()
        model.openAuthor("author")
        val first = ReadingTestModel().also { model.entries.value.single().viewModelStore.put("profile", it) }
        model.synchronize(Access, 0)
        assertFalse(first.cleared)
        model.synchronize(Access.copy(profile = false), 0)
        assertTrue(first.cleared)
        assertTrue(model.entries.value.isEmpty())
        model.synchronize(Access, 0)
        model.openAuthor("another")
        val second = ReadingTestModel().also { model.entries.value.single().viewModelStore.put("profile", it) }
        model.synchronize(Access, 1)
        assertTrue(second.cleared)
        assertTrue(model.entries.value.isEmpty())
    }

    @Test fun knownSourcesPreserveContentKindAndUnknownTargetsStayUnavailable() {
        fun reference(origin: DynamicOrigin, id: String = "42") = DynamicReference(origin, id, "", "", emptyList())
        assertEquals(CommunityDestination.Post(42), reference(DynamicOrigin.Guide).destination())
        assertEquals(CommunityDestination.Dynamic(42), reference(DynamicOrigin.Dynamic).destination())
        assertEquals(CommunityDestination.Recruitment(42, RecruitmentBoardKind.RolePlay), reference(DynamicOrigin.RolePlayRecruitment).destination())
        assertNull(reference(DynamicOrigin.Unknown).destination())
        assertNull(reference(DynamicOrigin.Original).destination())
        assertNull(reference(DynamicOrigin.Post, "invalid").destination())
        assertEquals(CommunityDestination.GuildPhoto(42), MessageTarget(MessageTargetKind.GuildPhoto, 42).destination())
        assertEquals(CommunityDestination.Recruitment(42, RecruitmentBoardKind.Guild),
            MessageTarget(MessageTargetKind.Recruitment, 42, MessageRecruitmentChannel.Guild).destination())
        assertEquals(CommunityDestination.Post(42), ProfileContentTarget(ProfileContentKind.Guide, 42).destination())
    }

    @Test fun photoMessagesNeedOnlyPhotoIdAndDedicatedCapability() {
        val model = CommunityReadingNavigation()
        model.synchronize(CommunityReadingAccess(guildRecruitment = true), 0)
        val target = MessageTarget(MessageTargetKind.GuildPhoto, 42).destination()!!
        model.open(target)
        assertTrue(model.entries.value.isEmpty())
        model.synchronize(CommunityReadingAccess(guild = true, profile = true), 0)
        model.open(CommunityDestination.GuildPhoto(0))
        assertTrue(model.entries.value.isEmpty())
        model.open(target)
        val photo = model.entries.value.single()
        model.openAuthor("photo-author")
        model.back()
        assertSame(photo, model.entries.value.single())
        model.synchronize(CommunityReadingAccess(profile = true), 0)
        assertTrue(model.entries.value.isEmpty())
    }

    private fun navigation() = CommunityReadingNavigation().apply { synchronize(Access, 0) }
    private companion object { val Access = CommunityReadingAccess(true, true, true, true, true) }
}

private class ReadingTestModel : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
}
