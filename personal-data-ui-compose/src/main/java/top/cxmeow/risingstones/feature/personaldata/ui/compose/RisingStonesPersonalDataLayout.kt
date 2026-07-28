package top.cxmeow.risingstones.feature.personaldata.ui.compose

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
