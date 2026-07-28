package top.cxmeow.risingstones.feature.forum.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class RisingStonesForumLayoutTest {
    @Test
    fun usesSharedAndroidWindowWidthBreakpoints() {
        assertEquals(RisingStonesForumLayoutMode.Compact, risingStonesForumLayoutMode(599))
        assertEquals(RisingStonesForumLayoutMode.Medium, risingStonesForumLayoutMode(600))
        assertEquals(RisingStonesForumLayoutMode.Medium, risingStonesForumLayoutMode(839))
        assertEquals(RisingStonesForumLayoutMode.Expanded, risingStonesForumLayoutMode(840))
    }
}
