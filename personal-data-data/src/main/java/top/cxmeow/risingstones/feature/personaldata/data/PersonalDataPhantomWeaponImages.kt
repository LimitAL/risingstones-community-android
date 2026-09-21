package top.cxmeow.risingstones.feature.personaldata.data

import top.cxmeow.risingstones.feature.personaldata.domain.PhantomWeaponElement

// chunk-7690576a.97d2f158.js, 9da6: getOtherItemIconUrl/getMKDItemIconUrl.
// These material icons have dedicated official paths; weapon icons use the ordinary item catalog.
internal fun phantomWeaponItemIconUrl(iconId: Int): String? {
    if (iconId <= 0) return null
    val icon = iconId.toString().padStart(6, '0')
    return when (iconId) {
        26025, 26035, 26034, 26026, 26027, 26029 -> "$PhantomImageBase/${icon}_hr1.png"
        26229, 26231, 26230 -> "$PhantomImageBase/item/${icon}_hr1.png"
        else -> personalDataItemIconUrl(iconId)
    }
}

// 9da6.aetherwellArrayDataComputed -> getElementalIconUrl; colors are not element file names.
internal fun phantomWeaponElementIconUrl(element: PhantomWeaponElement): String {
    val name = when (element) {
        PhantomWeaponElement.Yellow -> "earth"
        PhantomWeaponElement.Red -> "fire"
        PhantomWeaponElement.Blue -> "water"
        PhantomWeaponElement.Green -> "wind"
    }
    return "$PhantomImageBase/$name.png"
}

// 9da6.weaponPhase3CurrentComputed: 0cce/2675/76c7/ab62; completed render uses 9fa1.
internal fun phantomWeaponLensImageUrl(step: Int): String? {
    val image = when (step) {
        1 -> "c2497d0941516b81"
        2 -> "bf9ca5eb4a3b8e76"
        3 -> "611b7f59f8536f19"
        4 -> "ec05a7bf5a8e6ea2"
        5 -> "98769429037e6712"
        else -> return null
    }
    return "https://ff14risingstones.web.sdo.com/mob/static/images/ff14_$image.png"
}

private const val PhantomImageBase = "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/statistics/occult"
