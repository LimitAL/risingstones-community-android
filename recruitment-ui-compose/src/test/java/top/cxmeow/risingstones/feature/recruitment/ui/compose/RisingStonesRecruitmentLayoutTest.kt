package top.cxmeow.risingstones.feature.recruitment.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class RisingStonesRecruitmentLayoutTest {
    @Test
    fun usesSharedAndroidWindowWidthBreakpoints() {
        assertEquals(RisingStonesRecruitmentLayoutMode.Compact, risingStonesRecruitmentLayoutMode(599))
        assertEquals(RisingStonesRecruitmentLayoutMode.Medium, risingStonesRecruitmentLayoutMode(600))
        assertEquals(RisingStonesRecruitmentLayoutMode.Medium, risingStonesRecruitmentLayoutMode(839))
        assertEquals(RisingStonesRecruitmentLayoutMode.Expanded, risingStonesRecruitmentLayoutMode(840))
    }
}
