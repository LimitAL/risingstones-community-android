package top.cxmeow.risingstones.feature.dynamic.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicLayoutTest {
    @Test fun layoutBoundaries() {
        assertEquals(DynamicLayoutMode.Compact, dynamicLayoutMode(599))
        assertEquals(DynamicLayoutMode.Medium, dynamicLayoutMode(600))
        assertEquals(DynamicLayoutMode.Medium, dynamicLayoutMode(839))
        assertEquals(DynamicLayoutMode.Expanded, dynamicLayoutMode(840))
    }
}
