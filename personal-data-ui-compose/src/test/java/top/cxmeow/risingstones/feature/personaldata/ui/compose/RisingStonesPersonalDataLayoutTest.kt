package top.cxmeow.risingstones.feature.personaldata.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class RisingStonesPersonalDataLayoutTest {
    @Test
    fun usesSharedAndroidWindowWidthBreakpoints() {
        assertEquals(
            RisingStonesPersonalDataLayoutMode.Compact,
            risingStonesPersonalDataLayoutMode(599),
        )
        assertEquals(
            RisingStonesPersonalDataLayoutMode.Medium,
            risingStonesPersonalDataLayoutMode(600),
        )
        assertEquals(
            RisingStonesPersonalDataLayoutMode.Medium,
            risingStonesPersonalDataLayoutMode(839),
        )
        assertEquals(
            RisingStonesPersonalDataLayoutMode.Expanded,
            risingStonesPersonalDataLayoutMode(840),
        )
    }
}
