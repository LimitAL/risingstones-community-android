package top.cxmeow.risingstones.feature.profile.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class ProfileLayoutTest {
    @Test fun breakpoints() {
        assertEquals(ProfileLayoutMode.Compact, profileLayoutMode(599))
        assertEquals(ProfileLayoutMode.Medium, profileLayoutMode(600))
        assertEquals(ProfileLayoutMode.Medium, profileLayoutMode(839))
        assertEquals(ProfileLayoutMode.Expanded, profileLayoutMode(840))
    }

    @Test fun fractionalDensityKeepsExactPixelBreakpoints() {
        for (density in listOf(1f, 2.625f, 1920f / 600f, 1920f / 840f)) {
            val medium = (600 * density).roundToInt()
            val expanded = (840 * density).roundToInt()
            assertEquals(ProfileLayoutMode.Compact, profileLayoutMode(medium - 1, density))
            assertEquals(ProfileLayoutMode.Medium, profileLayoutMode(medium, density))
            assertEquals(ProfileLayoutMode.Medium, profileLayoutMode(expanded - 1, density))
            assertEquals(ProfileLayoutMode.Expanded, profileLayoutMode(expanded, density))
        }
        assertEquals(ProfileLayoutMode.Expanded, profileLayoutMode(1920, 1920f / 840f))
    }
}
