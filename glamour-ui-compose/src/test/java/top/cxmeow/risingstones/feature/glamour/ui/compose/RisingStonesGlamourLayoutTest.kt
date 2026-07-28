package top.cxmeow.risingstones.feature.glamour.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class RisingStonesGlamourLayoutTest {
    @Test
    fun usesSharedAndroidWindowWidthBreakpoints() {
        assertEquals(RisingStonesGlamourLayoutMode.Compact, risingStonesGlamourLayoutMode(599))
        assertEquals(RisingStonesGlamourLayoutMode.Medium, risingStonesGlamourLayoutMode(600))
        assertEquals(RisingStonesGlamourLayoutMode.Medium, risingStonesGlamourLayoutMode(839))
        assertEquals(RisingStonesGlamourLayoutMode.Expanded, risingStonesGlamourLayoutMode(840))
    }
}
