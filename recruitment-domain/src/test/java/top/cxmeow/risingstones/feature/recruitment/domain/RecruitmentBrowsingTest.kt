package top.cxmeow.risingstones.feature.recruitment.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecruitmentBrowsingTest {
    @Test
    fun knownDutyTypesUseTheOfficialTeamDefaultsWhileUnknownTypesStayUnset() {
        assertEquals("满编小队", dutyRecruitmentTeamCompositionForType("绝境战"))
        assertEquals("满编小队", dutyRecruitmentTeamCompositionForType("零式"))
        assertEquals("轻锐小队", dutyRecruitmentTeamCompositionForType("多变迷宫"))
        assertEquals("团队", dutyRecruitmentTeamCompositionForType("诛灭战"))
        for (type in listOf("", "全部", "其他", "Future type")) assertEquals("", dutyRecruitmentTeamCompositionForType(type))
    }

    @Test
    fun positionsFollowKnownTeamCompositionWithoutGuessingUnknownTypes() {
        assertEquals(listOf("MT", "ST", "H1", "H2", "D1", "D2", "D3", "D4"),
            DutyRecruitmentPosition.optionsForTeamComposition("满编小队").map { it.wireValue })
        assertEquals(DutyRecruitmentPosition.optionsForTeamComposition("满编小队"),
            DutyRecruitmentPosition.optionsForTeamComposition("团队"))
        assertEquals(listOf("T", "H", "D1", "D2"),
            DutyRecruitmentPosition.optionsForTeamComposition("轻锐小队").map { it.wireValue })
        for (type in listOf("", "其他", "全部队伍", "Future team")) {
            assertTrue(DutyRecruitmentPosition.optionsForTeamComposition(type).isEmpty())
        }
    }

    @Test
    fun catalogPreservesTypeOrderAndShowsHigherWeightDutiesFirst() {
        val catalog = DutyRecruitmentCatalogs(duties = listOf(
            DutyRecruitmentDutyConfig("1", "First type", "Low", "满编小队", 1),
            DutyRecruitmentDutyConfig("2", "Second type", "Other", "轻锐小队", 100),
            DutyRecruitmentDutyConfig("3", "First type", "High", "满编小队", 20),
        ))
        assertEquals(listOf("First type", "Second type"), catalog.dutyTypes)
        assertEquals(listOf("High", "Low"), catalog.dutyNames("First type"))
    }

    @Test
    fun optionalQueriesRetainLegacyPositionAndUseTheOfficialReviewDefault() {
        val old = DutyRecruitmentListQuery(position = DutyRecruitmentPosition.Healer1)
        assertEquals(listOf(DutyRecruitmentPosition.Healer1), DutyRecruitmentBrowseQuery(old).positions)
        assertEquals(RolePlayRecruitmentReviewOrder.Latest, RolePlayRecruitmentReviewQuery(42).order)
        assertEquals(30, RecruitmentContactMaximumLength)
    }
}
