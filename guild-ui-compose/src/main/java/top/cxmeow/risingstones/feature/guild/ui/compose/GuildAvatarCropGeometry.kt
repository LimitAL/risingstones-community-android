package top.cxmeow.risingstones.feature.guild.ui.compose

import kotlin.math.roundToInt

/** Coordinates refer to the image after applying its EXIF orientation. */
internal data class GuildAvatarCropSelection(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    init { require(centerX.isFinite() && centerY.isFinite() && zoom.isFinite()) }

    fun constrainedTo(width: Int, height: Int): GuildAvatarCropSelection {
        require(width > 0 && height > 0)
        val boundedZoom = zoom.coerceIn(1f, 4f)
        val side = minOf(width, height) / boundedZoom
        val halfX = side / (2f * width)
        val halfY = side / (2f * height)
        return GuildAvatarCropSelection(
            centerX.coerceIn(halfX, 1f - halfX),
            centerY.coerceIn(halfY, 1f - halfY),
            boundedZoom,
        )
    }

    fun rectangle(width: Int, height: Int): GuildAvatarCropRectangle {
        val selection = constrainedTo(width, height)
        val side = (minOf(width, height) / selection.zoom).roundToInt().coerceIn(1, minOf(width, height))
        return GuildAvatarCropRectangle(
            (selection.centerX * width - side / 2f).roundToInt().coerceIn(0, width - side),
            (selection.centerY * height - side / 2f).roundToInt().coerceIn(0, height - side),
            side,
        )
    }
}

internal data class GuildAvatarCropRectangle(val left: Int, val top: Int, val side: Int)
