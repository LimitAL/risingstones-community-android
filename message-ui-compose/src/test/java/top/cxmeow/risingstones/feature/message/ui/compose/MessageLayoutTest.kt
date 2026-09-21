package top.cxmeow.risingstones.feature.message.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageLayoutTest {
    @Test fun widthBoundaries() {
        assertEquals(MessageLayoutMode.Compact, messageLayoutMode(599))
        assertEquals(MessageLayoutMode.Medium, messageLayoutMode(600))
        assertEquals(MessageLayoutMode.Medium, messageLayoutMode(839))
        assertEquals(MessageLayoutMode.Expanded, messageLayoutMode(840))
    }
}
