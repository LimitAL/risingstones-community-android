package top.cxmeow.risingstones.feature.personaldata.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt
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

    @Test
    fun exact840PixelsRemainExpandedAtNonIntegerDensity() {
        val density = 1920f / 840f
        assertTrue((1920 / density).toInt() < 840)
        assertEquals(RisingStonesPersonalDataLayoutMode.Expanded, personalDataLayoutFromPixels(1920, density))
        assertEquals(RisingStonesPersonalDataLayoutMode.Medium, personalDataLayoutFromPixels(1919, density))
    }

    @Test
    fun physicalPixelThresholdsPreserveAllFourWidthBoundaries() {
        for (density in listOf(1.25f, 2.625f, 1920f / 840f)) {
            for ((width, mode) in listOf(599 to RisingStonesPersonalDataLayoutMode.Compact,
                600 to RisingStonesPersonalDataLayoutMode.Medium, 839 to RisingStonesPersonalDataLayoutMode.Medium,
                840 to RisingStonesPersonalDataLayoutMode.Expanded)) {
                assertEquals("$width at $density", mode, personalDataLayoutFromPixels((width * density).roundToInt(), density))
            }
        }
    }
}
