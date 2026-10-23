package top.cxmeow.risingstones.feature.recruitment.domain
import org.junit.Assert.*
import org.junit.Test
private fun catalog() = DutyRecruitmentFilterCatalog(DutyRecruitmentCatalogs((1..12).associate { "$it" to DutyRecruitmentJob("$it", "Mock job $it", null, "防护职业") }, listOf(
    DutyRecruitmentDutyConfig("1", "绝境战", "Mock duty", "满编小队", 1), DutyRecruitmentDutyConfig("2", "多变迷宫", "Mock four", "轻锐小队", 2), DutyRecruitmentDutyConfig("3", "诛灭战", "Mock alliance", "团队", 3))), listOf(DutyRecruitmentLabel("1", "Mock label", 1)), listOf(CommunityRecruitmentArea("1", "Mock area", emptyList()), CommunityRecruitmentArea("0", "All", emptyList())))
private fun validForm() = DutyRecruitmentPublishForm(dutyName = "Mock duty", targetAreaId = 1, neededJobIds = listOf("1"), labelIds = listOf("1"), texts = mapOf(DutyPublishField.Progress to "Mock progress", DutyPublishField.Strategy to "Mock strategy", DutyPublishField.Schedule to "Mock schedule", DutyPublishField.Contact to "Mock contact"))
class DutyRecruitmentPublishingTest {
    @Test fun defaultsRequireRealCatalogSelectionsAndFields() {
        val issues=DutyRecruitmentPublishForm().issues(catalog()); assertTrue(DutyPublishField.Area in issues); assertTrue(DutyPublishField.Needed in issues)
        assertNull(DutyRecruitmentPublishForm().createDraft(catalog(),true)); assertTrue(validForm().issues(catalog()).isEmpty())
    }
    @Test fun utf16LimitsDoNotTrimOrTruncateInputAndDraftTrimsOnlyAfterValidation() {
        val f=validForm().copy(texts=validForm().texts+(DutyPublishField.Progress to "😀".repeat(8)))
        assertEquals(DutyPublishIssue.TooLong,f.issues(catalog())[DutyPublishField.Progress]); assertEquals(16,f.text(DutyPublishField.Progress).length)
        val ok=validForm().copy(texts=validForm().texts+(DutyPublishField.Contact to "  mock  ")); assertEquals("mock",ok.createDraft(catalog(),true)!!.form.text(DutyPublishField.Contact))
    }
    @Test fun typesFollowFirstCatalogEntryAndNamesUseWeightAndRemoveDuplicates() {
        val c=catalog();assertEquals(listOf("绝境战","多变迷宫","诛灭战","其他"),c.publishTypes().map { it.name })
        assertEquals(listOf("Mock duty"),c.catalogs.dutyNames("绝境战"));assertEquals(DutyTeamComposition.FullParty,validForm().composition(c))
    }
    @Test fun changingTypeClearsNamesAndPrunesOnlyIncompatibleSlots() {
        val f=validForm().copy(occupiedJobs=mapOf("MT" to "1","D1" to "2")); val four=f.selectType("多变迷宫",catalog())
        assertEquals("",four.dutyName);assertEquals(mapOf("D1" to "2"),four.occupiedJobs)
        assertEquals(24,DutyTeamComposition.Alliance.slots.size);assertTrue("C.D4" in DutyTeamComposition.Alliance.slots)
    }
    @Test fun otherTypeSupportsCustomCompositionAndNameButUnknownCompositionCannotBeAssumed() {
        val f=validForm().copy(dutyType="其他",customDutyName="Mock custom",customComposition=DutyTeamComposition.Other)
        assertTrue(f.issues(catalog()).isEmpty());assertTrue(f.composition(catalog())!!.slots.isEmpty())
        assertTrue(DutyPublishField.Composition in f.copy(dutyType="unknown").issues(catalog()))
    }
    @Test fun jobsAndTagsRespectLimitsAndCustomTagsDoNotReplaceRequiredCatalogTag() {
        var f=validForm();(2..12).forEach { f=f.toggleNeeded("$it") };assertEquals(10,f.neededJobIds.size)
        f=f.addCustomLabel("one").addCustomLabel("two").addCustomLabel("three");assertEquals(2,f.customLabels.size)
        assertEquals(f,f.addCustomLabel("one"));assertTrue(DutyPublishField.Labels in f.copy(labelIds=emptyList()).issues(catalog()))
    }
    @Test fun reconciliationRemovesUnavailableJobsLabelsNamesAndAllAreas() {
        val f=validForm().copy(dutyName="gone",targetAreaId=0,neededJobIds=listOf("gone","1","1"),occupiedJobs=mapOf("MT" to "gone"),labelIds=listOf("gone","1"))
        val r=f.reconcile(catalog());assertEquals("",r.dutyName);assertNull(r.targetAreaId);assertEquals(listOf("1"),r.neededJobIds);assertEquals(listOf("1"),r.labelIds);assertTrue(r.occupiedJobs.isEmpty())
    }
    @Test fun globalServersAllowedButAllUnknownAreasAndInvalidPeriodsAreRejected() {
        assertTrue(validForm().copy(targetAreaId=-1).issues(catalog()).isEmpty())
        listOf(0,999,-2).forEach { assertTrue(DutyPublishField.Area in validForm().copy(targetAreaId=it).issues(catalog())) }
        assertTrue(DutyPublishField.DueDays in validForm().copy(dueDays=4).issues(catalog()))
    }
    @Test fun listedAndUnlistedSnapshotsKeepSharingAndVisibilitySeparate() {
        val f=validForm().copy(sharesToDynamics=true,visibility=DutyPublishVisibility.OnlyMe,dueDays=15)
        assertTrue(f.createDraft(catalog(),true)!!.listsImmediately); val hidden=f.createDraft(catalog(),false)!!
        assertFalse(hidden.listsImmediately);assertTrue(hidden.form.sharesToDynamics);assertEquals(DutyPublishVisibility.OnlyMe,hidden.form.visibility)
    }
    @Test fun serverFieldMappingNeverGuessesUnknownFields() {
        assertEquals(DutyPublishField.Occupied,DutyPublishField.fromServer("team_position"));assertEquals(DutyPublishField.Contact,DutyPublishField.fromServer("contact_info"));assertNull(DutyPublishField.fromServer("future_field"))
    }
}
