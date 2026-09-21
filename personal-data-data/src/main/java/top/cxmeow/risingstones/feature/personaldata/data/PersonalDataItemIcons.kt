package top.cxmeow.risingstones.feature.personaldata.data

/** Official item icons use a thousand-item directory and at least six ASCII digits. */
internal fun personalDataItemIconUrl(iconId: Int): String? {
    if (iconId <= 0) return null
    val directory = (iconId / 1_000 * 1_000).toString().padStart(6, '0')
    val icon = iconId.toString().padStart(6, '0')
    return "https://ff14-eo.web.sdo.com/ffstones/item/icon/dcsvv4fowz2m/$directory/${icon}_hr1.png"
}
