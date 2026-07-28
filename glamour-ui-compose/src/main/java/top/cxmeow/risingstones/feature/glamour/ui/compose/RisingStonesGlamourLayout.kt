package top.cxmeow.risingstones.feature.glamour.ui.compose

enum class RisingStonesGlamourLayoutMode {
    Compact,
    Medium,
    Expanded,
}

fun risingStonesGlamourLayoutMode(widthDp: Int): RisingStonesGlamourLayoutMode = when {
    widthDp < 600 -> RisingStonesGlamourLayoutMode.Compact
    widthDp < 840 -> RisingStonesGlamourLayoutMode.Medium
    else -> RisingStonesGlamourLayoutMode.Expanded
}
