package top.cxmeow.risingstones.feature.forum.ui.compose

enum class RisingStonesForumLayoutMode {
    Compact,
    Medium,
    Expanded,
}

fun risingStonesForumLayoutMode(widthDp: Int): RisingStonesForumLayoutMode = when {
    widthDp < 600 -> RisingStonesForumLayoutMode.Compact
    widthDp < 840 -> RisingStonesForumLayoutMode.Medium
    else -> RisingStonesForumLayoutMode.Expanded
}
