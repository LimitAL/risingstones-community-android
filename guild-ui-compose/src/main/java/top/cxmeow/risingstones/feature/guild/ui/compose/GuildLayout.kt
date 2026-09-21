package top.cxmeow.risingstones.feature.guild.ui.compose

enum class GuildLayoutMode { Compact, Medium, Expanded }

fun guildLayoutMode(widthDp: Int): GuildLayoutMode = when {
    widthDp < 600 -> GuildLayoutMode.Compact
    widthDp < 840 -> GuildLayoutMode.Medium
    else -> GuildLayoutMode.Expanded
}
