package top.cxmeow.risingstones.feature.guild.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuildAvatarCropGeometryTest {
    @Test fun defaultCropCentersLandscapeAndPortraitImages() {
        assertEquals(GuildAvatarCropRectangle(100, 0, 200), GuildAvatarCropSelection().rectangle(400, 200))
        assertEquals(GuildAvatarCropRectangle(0, 100, 200), GuildAvatarCropSelection().rectangle(200, 400))
    }

    @Test fun panAndZoomAlwaysStayInsideImageIncludingTinyInputs() {
        for ((width, height) in listOf(400 to 200, 200 to 400, 1 to 1)) {
            for (x in listOf(-10f, 0f, 0.5f, 1f, 10f)) for (y in listOf(-10f, 0f, 1f, 10f)) {
                for (zoom in listOf(-1f, 1f, 2f, 4f, 100f)) {
                    val rect = GuildAvatarCropSelection(x, y, zoom).rectangle(width, height)
                    assertTrue(rect.left >= 0 && rect.top >= 0 && rect.side >= 1)
                    assertTrue(rect.left + rect.side <= width && rect.top + rect.side <= height)
                }
            }
        }
    }

    @Test fun selectionIsResolutionIndependentForPreviewAndExport() {
        val selection = GuildAvatarCropSelection(0.75f, 0.25f, 2f)
        val preview = selection.rectangle(400, 200)
        val export = selection.rectangle(800, 400)
        assertEquals(GuildAvatarCropRectangle(preview.left * 2, preview.top * 2, preview.side * 2), export)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteSelectionIsRejected() { GuildAvatarCropSelection(zoom = Float.NaN) }
}
