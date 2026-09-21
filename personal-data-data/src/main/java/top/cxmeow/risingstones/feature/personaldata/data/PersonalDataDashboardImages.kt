package top.cxmeow.risingstones.feature.personaldata.data

internal fun personalDataAchievementIconUrl(iconId: Int): String? {
    if (iconId <= 0) return null
    val icon = iconId.toString().padStart(6, '0')
    return "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/achievements/icon/${icon}_hr1.png"
}

internal fun personalDataRaidImageUrl(imageId: Int): String? {
    if (imageId <= 0) return null
    return "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/savage/loading/${imageId}_hr1.png"
}
