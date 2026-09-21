package top.cxmeow.risingstones.feature.personaldata.data

/**
 * 公开来源（2026-09-20 核对）：
 * https://ff14risingstones.web.sdo.com/mob/static/js/chunk-5427d794.02834a98.js
 * 762c.b 提供名称、category、abbr；dc50 的 getHollowedJobIcon / getDoWAndDoMUrl 拼接图片。
 * 这是名称到图片的目录，不能用于判断某职业是否具有前线参战资格。
 */
internal fun personalDataFrontlineJobIconUrl(jobName: String, hollow: Boolean = true): String? {
    val path = frontlineJobImagePaths[jobName] ?: return null
    val suffix = if (hollow) "_hollow" else ""
    return "$frontlineImageBase/job/$path$suffix.png"
}

/**
 * dc50.flagSrc 按 total.gc_id 的军团名称选择 fb94 中的旗帜；1dd8/dbd6/07a8 导出以下文件。
 * 2026-09-20 匿名浏览器核对：移动入口 publicPath 为空且无 base，三个 /mob/static/images 资源均为 PNG。
 */
internal fun personalDataFrontlineGrandCompanyImageUrl(grandCompanyName: String): String? {
    val fileName = when (grandCompanyName) {
        "恒辉队" -> "ff14_8039bacdf367c87e.png"
        "双蛇党" -> "ff14_608b95c61b087678.png"
        "黑涡团" -> "ff14_7ba5c921c4c67b6f.png"
        else -> return null
    }
    return "https://ff14risingstones.web.sdo.com/mob/static/images/$fileName"
}

/** dc50 的已解锁和未解锁成就均直接使用 r7.png，不以成就编号或普通成就图标公式替换。 */
internal fun personalDataFrontlineAchievementImageUrl(): String = "$frontlineImageBase/r7.png"

/** dc50.mapSelects 使用名称过滤 territory_type；原目录的 value 均为空，不能推导数字地图编号。 */
internal val personalDataFrontlineMapNames: List<String>
    get() = listOf("昂萨哈凯尔", "尘封秘岩", "荣誉野", "周边遗迹群", "沃刻其特")

private const val frontlineImageBase = "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones"

private val frontlineJobImagePaths = mapOf(
    "骑士" to "DoW/PLD",
    "战士" to "DoW/WAR",
    "暗黑骑士" to "DoW/DRK",
    "绝枪战士" to "DoW/GNB",
    "白魔法师" to "DoM/WHM",
    "学者" to "DoM/SCH",
    "占星术士" to "DoM/AST",
    "贤者" to "DoM/SGE",
    "武僧" to "DoW/MNK",
    "龙骑士" to "DoW/DRG",
    "忍者" to "DoW/NIN",
    "武士" to "DoW/SAM",
    "钐镰客" to "DoW/RPR",
    "蝰蛇剑士" to "DoW/VPR",
    "驯兽师" to "DoW/BST",
    "吟游诗人" to "DoW/BRD",
    "机工士" to "DoW/MCH",
    "舞者" to "DoW/DNC",
    "黑魔法师" to "DoM/BLM",
    "召唤师" to "DoM/SMN",
    "赤魔法师" to "DoM/RDM",
    "绘灵法师" to "DoM/PCT",
    "青魔法师" to "DoM/BLU",
)
