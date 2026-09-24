package top.cxmeow.risingstones.app

import androidx.lifecycle.ViewModel
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import top.cxmeow.risingstones.core.auth.*
import top.cxmeow.risingstones.feature.dynamic.domain.*
import top.cxmeow.risingstones.feature.message.domain.*
import top.cxmeow.risingstones.feature.profile.domain.*
import top.cxmeow.risingstones.feature.recruitment.presentation.RecruitmentBoardKind

class CommunityReadingNavigationTest {
    @Test fun actualSessionReadRevocationClearsGuildEvenWhenWriteAndDynamicRemain() {
        val before = RisingStonesSessionState.Active(RisingStonesCredentialSource.WebCookie, setOf(
            RisingStonesCapability.AccountRead, RisingStonesCapability.DynamicRead,
            RisingStonesCapability.RecruitmentWrite, RisingStonesCapability.RecruitmentAuthenticated,
        ))
        val after = before.copy(capabilities = before.capabilities - RisingStonesCapability.RecruitmentAuthenticated)
        val beforeAccess = communityReadingAccess(before)
        val afterAccess = communityReadingAccess(after)
        assertTrue(afterAccess.dynamic)
        assertFalse(afterAccess.guildRecruitment)
        for (origin in listOf(DynamicOrigin.GuildRecruitment, DynamicOrigin.OtherRecruitment)) {
            val navigation = CommunityReadingNavigation()
            navigation.synchronize(beforeAccess, 0)
            navigation.open(CommunityDestination.DynamicRecruitmentRelay(42, origin, "Source"))
            val probe = ReadingTestModel().also { navigation.entries.value.single().viewModelStore.put("probe", it) }
            navigation.synchronize(afterAccess, 0)
            assertEquals(origin == DynamicOrigin.GuildRecruitment, probe.cleared)
            assertEquals(origin == DynamicOrigin.GuildRecruitment, navigation.entries.value.isEmpty())
            navigation.clear()
        }
    }

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

    @Test fun dynamicComposerKeepsRelaySourceOnlyInItsLiveEntryAndNeedsDynamicRead() {
        val model = CommunityReadingNavigation()
        model.synchronize(CommunityReadingAccess(), 0)
        model.open(CommunityDestination.DynamicComposer(42, "Already read title"))
        assertTrue(model.entries.value.isEmpty())

        model.synchronize(Access, 0)
        model.open(CommunityDestination.DynamicComposer(42, "Already read title"))
        val entry = model.entries.value.single()
        assertEquals(
            CommunityDestination.DynamicComposer(42, "Already read title"),
            entry.destination,
        )
        val draft = ReadingTestModel().also { entry.viewModelStore.put("publishing-draft", it) }

        model.synchronize(Access, 1)
        assertTrue(draft.cleared)
        assertTrue(model.entries.value.isEmpty())
    }

    @Test fun dynamicComposerRejectsInvalidRelayIdentityOrDetachedTitle() {
        assertThrows(IllegalArgumentException::class.java) {
            CommunityDestination.DynamicComposer(0, "Title")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommunityDestination.DynamicComposer(relayPostTitle = "Title")
        }
    }

    @Test fun dynamicPublishingEntryNeedsReadAccessPublishingAndExplicitWriteEligibility() {
        val readingOnly = proxyService(DynamicService::class.java) as DynamicService
        val publishing = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DynamicService::class.java, DynamicPublishingService::class.java),
        ) { _, method, _ -> when (method.name) {
            "getCanRead" -> true
            else -> error("Unexpected publishing service call: ${method.name}")
        } } as DynamicService

        assertFalse(canOpenDynamicPublishing(true, readingOnly, actionService(false, true)))
        assertFalse(canOpenDynamicPublishing(false, publishing, actionService(false, true)))
        assertFalse(canOpenDynamicPublishing(true, publishing, actionService(false, false)))
        assertTrue(canOpenDynamicPublishing(true, publishing, actionService(false, true)))
        assertTrue(canOpenDynamicPublishing(true, publishing, actionService(true, false)))
    }

    @Test fun everyRecruitmentBoardMapsToItsDedicatedDynamicOrigin() {
        assertEquals(DynamicOrigin.DutyRecruitment, RecruitmentBoardKind.Duty.dynamicOrigin())
        assertEquals(DynamicOrigin.BeginnerRecruitment, RecruitmentBoardKind.Beginner.dynamicOrigin())
        assertEquals(DynamicOrigin.GuildRecruitment, RecruitmentBoardKind.Guild.dynamicOrigin())
        assertEquals(DynamicOrigin.OtherRecruitment, RecruitmentBoardKind.Other.dynamicOrigin())
        assertEquals(DynamicOrigin.RolePlayRecruitment, RecruitmentBoardKind.RolePlay.dynamicOrigin())
    }

    @Test fun recruitmentRelayNeedsDynamicReadAndGuildSourceKeepsItsExtraReadGate() {
        val model = CommunityReadingNavigation()
        val duty = CommunityDestination.DynamicRecruitmentRelay(
            42,
            DynamicOrigin.DutyRecruitment,
            "Duty source",
        )
        val guild = CommunityDestination.DynamicRecruitmentRelay(
            43,
            DynamicOrigin.GuildRecruitment,
            "Guild source",
        )
        model.synchronize(CommunityReadingAccess(), 0)
        model.open(duty)
        assertTrue(model.entries.value.isEmpty())

        model.synchronize(CommunityReadingAccess(dynamic = true), 0)
        model.open(duty)
        assertEquals(duty, model.entries.value.single().destination)
        val dutyRetained = ReadingTestModel().also {
            model.entries.value.single().viewModelStore.put("duty-relay", it)
        }
        model.synchronize(CommunityReadingAccess(), 0)
        assertTrue(dutyRetained.cleared)
        assertTrue(model.entries.value.isEmpty())

        model.synchronize(CommunityReadingAccess(dynamic = true), 0)
        model.open(guild)
        assertTrue(model.entries.value.isEmpty())

        model.synchronize(CommunityReadingAccess(dynamic = true, guildRecruitment = true), 0)
        model.open(guild)
        val retained = ReadingTestModel().also {
            model.entries.value.single().viewModelStore.put("guild-relay", it)
        }
        model.synchronize(CommunityReadingAccess(dynamic = true), 0)
        assertTrue(retained.cleared)
        assertTrue(model.entries.value.isEmpty())
    }

    @Test fun recruitmentRelayRejectsNonRecruitmentOriginsAndNeedsDedicatedService() {
        assertThrows(IllegalArgumentException::class.java) {
            CommunityDestination.DynamicRecruitmentRelay(42, DynamicOrigin.Post, "Post")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommunityDestination.DynamicRecruitmentRelay(0, DynamicOrigin.DutyRecruitment, "Duty")
        }

        val publishingOnly = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DynamicService::class.java, DynamicPublishingService::class.java),
        ) { _, method, _ -> when (method.name) {
            "getCanRead" -> true
            else -> error("Unexpected publishing service call: ${method.name}")
        } } as DynamicService
        val recruitmentRelay = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DynamicService::class.java, DynamicRecruitmentRelayService::class.java),
        ) { _, method, _ -> when (method.name) {
            "getCanRead" -> true
            else -> error("Unexpected recruitment relay service call: ${method.name}")
        } } as DynamicService
        assertFalse(canOpenDynamicRecruitmentRelay(true, publishingOnly, actionService(false, true)))
        assertFalse(canOpenDynamicRecruitmentRelay(false, recruitmentRelay, actionService(false, true)))
        assertFalse(canOpenDynamicRecruitmentRelay(true, recruitmentRelay, actionService(false, false)))
        assertTrue(canOpenDynamicRecruitmentRelay(true, recruitmentRelay, actionService(false, true)))
        assertTrue(canOpenDynamicRecruitmentRelay(true, recruitmentRelay, actionService(true, false)))
    }

    private fun navigation() = CommunityReadingNavigation().apply { synchronize(Access, 0) }
    private companion object { val Access = CommunityReadingAccess(true, true, true, true, true) }
}

private fun actionService(canPerform: Boolean, canAttempt: Boolean) = Proxy.newProxyInstance(
    DynamicActionService::class.java.classLoader,
    arrayOf(DynamicActionService::class.java),
) { _, method, _ -> when (method.name) {
    "getCanPerformAuthenticatedWrites" -> canPerform
    "getCanAttemptAuthenticatedWrites" -> canAttempt
    else -> error("Unexpected action service call: ${method.name}")
} } as DynamicActionService

private fun proxyService(type: Class<*>) = Proxy.newProxyInstance(
    type.classLoader,
    arrayOf(type),
) { _, method, _ -> when (method.name) {
    "getCanRead" -> true
    else -> error("Unexpected reading service call: ${method.name}")
} }

private class ReadingTestModel : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
}
