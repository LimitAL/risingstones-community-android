package top.cxmeow.risingstones.feature.personaldata.data

/**
 * 公开来源（2026-09-20 核对）：
 * https://ff14risingstones.web.sdo.com/mob/static/js/chunk-f27e63c4.14a654ff.js
 * SHA-256: 260612e1b09ba45df5d6ed5b0e42dd115b615b3a09a646a3fd22e485204d5067。
 * 3c80.instances 与图片模块 2041/6806/d6fc/927b/9aef/952a/a8f1 对应以下七个封面。
 * 移动入口 publicPath 为空且无 base；七张 /mob/static/images 图片均已匿名核对为 HTTP 200 PNG。
 */
internal fun personalDataUltimateCoverUrl(territoryType: Int): String? {
    val image = when (territoryType) {
        733 -> "03d05c7264a9e0e5"
        777 -> "5b2d6831aac2af1d"
        887 -> "37021af70e09f86c"
        968 -> "e1176e944b308333"
        1122 -> "d38ad293f3237cb6"
        1238 -> "39cd1fd5bf0ae3ce"
        // 官网为妖星乱舞显式选择 default.png；未知副本不共享这个回退。
        1363 -> "191b305bd9531bfa"
        else -> return null
    }
    return "https://ff14risingstones.web.sdo.com/mob/static/images/ff14_$image.png"
}

/** 两页面的 762c.b 目录逐字相同，3c80.getDoWAndDoMUrl 使用普通 category/abbr.png。 */
internal fun personalDataUltimateJobIconUrl(jobName: String): String? =
    personalDataFrontlineJobIconUrl(jobName, hollow = false)

/**
 * 3c80.team 按 762c.b.type 的防护、治疗、进攻顺序排序，同组 comparator 返回 0。
 * 这不是职业编号或完整目录索引；调用方保持同组输入顺序，将未知的 null 稳定后置。
 */
internal fun personalDataUltimateJobOrder(jobName: String): Int? = when (jobName) {
    "骑士", "战士", "暗黑骑士", "绝枪战士" -> 0
    "白魔法师", "学者", "占星术士", "贤者" -> 1
    "武僧", "龙骑士", "忍者", "武士", "钐镰客", "蝰蛇剑士", "驯兽师",
    "吟游诗人", "机工士", "舞者", "黑魔法师", "召唤师", "赤魔法师", "绘灵法师", "青魔法师" -> 2
    else -> null
}

/**
 * 3c80.instances 关联 9bee.a 的 medal_id；单绝境分享卡调用 getMedalUrl("medal" + id + ".png")。
 * 原生借用该已证实图片展示成就，不据 territory 编号猜测 medal_id，也不套物品图标公式。
 */
internal fun personalDataUltimateMedalImageUrl(territoryType: Int): String? {
    val medalId = when (territoryType) {
        733 -> 1
        777 -> 2
        887 -> 3
        968 -> 4
        1122 -> 25
        1238 -> 43
        1363 -> 46
        else -> return null
    }
    return "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones/medal/medal$medalId.png"
}
