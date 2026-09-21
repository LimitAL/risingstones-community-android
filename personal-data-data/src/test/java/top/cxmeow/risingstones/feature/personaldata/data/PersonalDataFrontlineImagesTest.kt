package top.cxmeow.risingstones.feature.personaldata.data

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalDataFrontlineImagesTest {
    @Test
    fun reviewedJobNamesResolveBothOfficialRenderVariants() {
        // 公开 762c.b 目录夹具，不表示这些职业均可参加前线。
        val jobs = mapOf(
            "骑士" to "DoW/PLD", "战士" to "DoW/WAR", "暗黑骑士" to "DoW/DRK",
            "绝枪战士" to "DoW/GNB", "白魔法师" to "DoM/WHM", "学者" to "DoM/SCH",
            "占星术士" to "DoM/AST", "贤者" to "DoM/SGE", "武僧" to "DoW/MNK",
            "龙骑士" to "DoW/DRG", "忍者" to "DoW/NIN", "武士" to "DoW/SAM",
            "钐镰客" to "DoW/RPR", "蝰蛇剑士" to "DoW/VPR", "驯兽师" to "DoW/BST",
            "吟游诗人" to "DoW/BRD", "机工士" to "DoW/MCH", "舞者" to "DoW/DNC",
            "黑魔法师" to "DoM/BLM", "召唤师" to "DoM/SMN", "赤魔法师" to "DoM/RDM",
            "绘灵法师" to "DoM/PCT", "青魔法师" to "DoM/BLU",
        )
        for ((name, path) in jobs) {
            assertEquals("$base/job/${path}_hollow.png", personalDataFrontlineJobIconUrl(name))
            assertEquals("$base/job/$path.png", personalDataFrontlineJobIconUrl(name, hollow = false))
        }
    }

    @Test
    fun unknownAndSyntheticJobNamesDoNotCreateGuessedUrls() {
        for (name in listOf("", "全部", "其他", "未知职业", "PLD", "19", " 骑士", "骑士 ", "騎士", "采矿工")) {
            assertNull(name, personalDataFrontlineJobIconUrl(name))
            assertNull(name, personalDataFrontlineJobIconUrl(name, hollow = false))
        }
    }

    @Test
    fun untrustedJobInputCannotAlterTheOfficialResourcePath() {
        for (name in listOf("../PLD", "DoW/PLD", "https://example.invalid/icon.png", "骑士?url=other", "骑士\u0000")) {
            assertNull(personalDataFrontlineJobIconUrl(name))
        }
        val url = URI(personalDataFrontlineJobIconUrl("骑士"))
        assertEquals("https", url.scheme)
        assertEquals("static.web.sdo.com", url.host)
        assertNull(url.userInfo)
        assertNull(url.query)
        assertNull(url.fragment)
    }

    @Test
    fun frontlineAchievementsUseTheFixedReviewedImage() {
        assertEquals("$base/r7.png", personalDataFrontlineAchievementImageUrl())
    }

    @Test
    fun grandCompanyNamesUseTheVerifiedMobileFlagResources() {
        val flags = mapOf(
            "恒辉队" to "ff14_8039bacdf367c87e.png",
            "双蛇党" to "ff14_608b95c61b087678.png",
            "黑涡团" to "ff14_7ba5c921c4c67b6f.png",
        )
        for ((name, fileName) in flags) {
            val value = personalDataFrontlineGrandCompanyImageUrl(name)
            assertEquals("https://ff14risingstones.web.sdo.com/mob/static/images/$fileName", value)
            val uri = URI(value)
            assertEquals("https", uri.scheme)
            assertEquals("ff14risingstones.web.sdo.com", uri.host)
            assertNull(uri.userInfo)
            assertNull(uri.query)
            assertNull(uri.fragment)
        }
    }

    @Test
    fun unknownGrandCompaniesAndTeamNumbersDoNotGuessFlags() {
        for (name in listOf(
            "", "未知军团", "恒輝隊", " 恒辉队", "恒辉队 ", "1", "2", "3", "team3", "hh",
            "theImmortalFlames", "../theImmortalFlames", "https://example.invalid/flag.png", "恒辉队\n",
        )) {
            assertNull(name, personalDataFrontlineGrandCompanyImageUrl(name))
        }
    }

    @Test
    fun mapsKeepTheOfficialNameBasedOrder() {
        assertEquals(
            listOf("昂萨哈凯尔", "尘封秘岩", "荣誉野", "周边遗迹群", "沃刻其特"),
            personalDataFrontlineMapNames,
        )
    }

    @Test
    fun callersCannotPolluteFutureMapCatalogReads() {
        val first = personalDataFrontlineMapNames
        val next = personalDataFrontlineMapNames
        assertNotSame(first, next)
        (first as MutableList<String>)[0] = "调用方更改"
        assertEquals("昂萨哈凯尔", personalDataFrontlineMapNames.first())
        assertEquals("昂萨哈凯尔", next.first())
    }

    private companion object {
        const val base = "https://static.web.sdo.com/jijiamobile/pic/ff14/ffstones"
    }
}
