package top.cxmeow.risingstones.feature.personaldata.ui.compose

import kotlin.math.roundToInt

enum class RisingStonesPersonalDataLayoutMode {
    Compact,
    Medium,
    Expanded,
}

fun risingStonesPersonalDataLayoutMode(widthDp: Int): RisingStonesPersonalDataLayoutMode = when {
    widthDp < 600 -> RisingStonesPersonalDataLayoutMode.Compact
    widthDp < 840 -> RisingStonesPersonalDataLayoutMode.Medium
    else -> RisingStonesPersonalDataLayoutMode.Expanded
}

internal fun personalDataLayoutFromPixels(widthPx: Int, density: Float): RisingStonesPersonalDataLayoutMode = when {
    widthPx < (600 * density).roundToInt() -> RisingStonesPersonalDataLayoutMode.Compact
    widthPx < (840 * density).roundToInt() -> RisingStonesPersonalDataLayoutMode.Medium
    else -> RisingStonesPersonalDataLayoutMode.Expanded
}
