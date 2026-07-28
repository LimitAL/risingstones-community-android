package top.cxmeow.risingstones.feature.recruitment.ui.compose

enum class RisingStonesRecruitmentLayoutMode {
    Compact,
    Medium,
    Expanded,
}

fun risingStonesRecruitmentLayoutMode(widthDp: Int): RisingStonesRecruitmentLayoutMode = when {
    widthDp < 600 -> RisingStonesRecruitmentLayoutMode.Compact
    widthDp < 840 -> RisingStonesRecruitmentLayoutMode.Medium
    else -> RisingStonesRecruitmentLayoutMode.Expanded
}
